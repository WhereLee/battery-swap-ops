package com.swapops.server.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.contract.OrderStatus;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.enums.OrderType;
import com.swapops.server.order.service.delay.OrderDelayService;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CellDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 订单事件驱动（S0.2 §4.3）：以设备事件（携 commandSeq 的会话回带）推进订单状态机，
 * 全部 CAS 迁移；重复事件在 CAS 上自然幂等吸收。无 commandSeq 的事件只走设备台账，不进订单域。
 */
@Slf4j
@Service
public class OrderEventService {

    private final SwapOrderDao orderDao;
    private final CellDao cellDao;
    private final BatteryDao batteryDao;
    private final BillingService billingService;
    private final SwapOrderService swapOrderService;
    private final OrderDelayService orderDelayService;

    public OrderEventService(SwapOrderDao orderDao, CellDao cellDao, BatteryDao batteryDao,
                             BillingService billingService, SwapOrderService swapOrderService,
                             OrderDelayService orderDelayService) {
        this.orderDao = orderDao;
        this.cellDao = cellDao;
        this.batteryDao = batteryDao;
        this.billingService = billingService;
        this.swapOrderService = swapOrderService;
        this.orderDelayService = orderDelayService;
    }

    /** 柜故障（S3.2）：该柜活跃订单全部转 EXCEPTION 交人工；在途指令由 DeviceEventService 中断 */
    public void onCabinetFault(CabinetEntity cabinet) {
        List<SwapOrderEntity> actives = orderDao.selectList(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getCabinetId, cabinet.getId())
                .in(SwapOrderEntity::getStatus,
                        OrderStatus.PENDING_OPEN.getCode(), OrderStatus.OPENED.getCode(),
                        OrderStatus.TAKEN.getCode(), OrderStatus.OVERDUE.getCode()));
        for (SwapOrderEntity order : actives) {
            swapOrderService.markException(order, "CABINET_FAULT");
        }
    }

    /** 门开事件：PENDING_OPEN → OPENED（校验目标仓一致） */
    public void onDoorOpened(CabinetEntity cabinet, Integer cellNo, Long commandSeq) {
        if (commandSeq == null) {
            return;
        }
        SwapOrderEntity order = findBySeq(cabinet, commandSeq, OrderStatus.PENDING_OPEN);
        if (order == null) {
            return;
        }
        CellEntity cell = order.getCellId() == null ? null : cellDao.selectById(order.getCellId());
        if (cell == null || !cell.getCellNo().equals(cellNo)) {
            log.warn("门开事件与订单目标仓不符，订单不推进 orderNo={} 事件cellNo={} 订单cellId={}",
                    order.getOrderNo(), cellNo, order.getCellId());
            return;
        }
        long now = System.currentTimeMillis();
        if (cas(order.getId(), OrderStatus.PENDING_OPEN, OrderStatus.OPENED,
                w -> w.set(SwapOrderEntity::getOpenTime, now))) {
            // 状态推进：撤预占计时，接取电计时（S3.3 精确计时器）
            orderDelayService.cancelAll(order);
            orderDelayService.schedulePickup(order, now);
            log.info("订单已开仓 orderNo={} cellNo={} seq={}", order.getOrderNo(), cellNo, commandSeq);
        }
    }

    /** 取电事件：TAKE 完成（借出绑定持有人+计费）；SWAP → TAKEN；RETURN 不应出现 */
    public void onBatteryOut(CabinetEntity cabinet, CellEntity cell, BatteryEntity battery, Long commandSeq) {
        if (commandSeq == null) {
            return;
        }
        SwapOrderEntity order = findBySeq(cabinet, commandSeq, OrderStatus.OPENED);
        if (order == null) {
            return;
        }
        OrderType type = OrderType.valueOf(order.getOrderType());
        long now = System.currentTimeMillis();
        switch (type) {
            case TAKE -> {
                if (cas(order.getId(), OrderStatus.OPENED, OrderStatus.COMPLETED, w -> w
                        .set(SwapOrderEntity::getTakeTime, now)
                        .set(SwapOrderEntity::getCompleteTime, now))) {
                    assignHolder(battery, order.getUserId());
                    order.setTakeTime(now);
                    order.setCompleteTime(now);
                    orderDelayService.cancelAll(order);
                    billingService.charge(order, now);
                    log.info("首借订单完成 orderNo={} batteryNo={}", order.getOrderNo(), battery.getBatteryNo());
                }
            }
            case SWAP -> {
                if (cas(order.getId(), OrderStatus.OPENED, OrderStatus.TAKEN,
                        w -> w.set(SwapOrderEntity::getTakeTime, now))) {
                    // 状态推进：撤取电计时，接归还超期计时（S3.3）
                    orderDelayService.cancelAll(order);
                    orderDelayService.scheduleOverdue(order, now);
                    log.info("换电订单已取电，待还旧电池 orderNo={} takeBatteryNo={}",
                            order.getOrderNo(), battery.getBatteryNo());
                }
            }
            case RETURN -> log.warn("RETURN 订单出现取电事件（不应发生） orderNo={} batteryNo={}",
                    order.getOrderNo(), battery.getBatteryNo());
        }
    }

    /** 还电事件：SWAP（TAKEN/OVERDUE→COMPLETED，持有人转移+计费，超期时段计入超时费）；RETURN（OPENED→COMPLETED，退押金） */
    public void onBatteryIn(CabinetEntity cabinet, CellEntity cell, BatteryEntity battery, Long commandSeq) {
        if (commandSeq == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // 超期订单（OVERDUE）归还同样完成：从实态 CAS 出发，避免超期后归还永远无法销单
        SwapOrderEntity swapOrder = orderDao.selectOne(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getCabinetId, cabinet.getId())
                .eq(SwapOrderEntity::getOpenCommandSeq, commandSeq)
                .in(SwapOrderEntity::getStatus, OrderStatus.TAKEN.getCode(), OrderStatus.OVERDUE.getCode())
                .last("LIMIT 1"));
        if (swapOrder != null && OrderType.SWAP.name().equals(swapOrder.getOrderType())) {
            BatteryEntity held = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                    .eq(BatteryEntity::getHolderUserId, swapOrder.getUserId()));
            if (held != null && !held.getBatteryNo().equals(battery.getBatteryNo())) {
                log.warn("归还电池与在持电池不一致（按设备事实入账，S3 对账） orderNo={} 在持={} 归还={}",
                        swapOrder.getOrderNo(), held.getBatteryNo(), battery.getBatteryNo());
            }
            OrderStatus from = OrderStatus.fromCode(swapOrder.getStatus());
            if (cas(swapOrder.getId(), from, OrderStatus.COMPLETED, w -> w
                    .set(SwapOrderEntity::getReturnTime, now)
                    .set(SwapOrderEntity::getCompleteTime, now)
                    .set(SwapOrderEntity::getReturnBatteryId, battery.getId()))) {
                if (swapOrder.getTakeBatteryId() != null) {
                    BatteryEntity takeBattery = batteryDao.selectById(swapOrder.getTakeBatteryId());
                    if (takeBattery != null) {
                        assignHolder(takeBattery, swapOrder.getUserId());
                    }
                }
                swapOrder.setReturnTime(now);
                swapOrder.setCompleteTime(now);
                orderDelayService.cancelAll(swapOrder);
                billingService.charge(swapOrder, now);
                log.info("换电订单完成 orderNo={} from={} 归还={} 借出={}", swapOrder.getOrderNo(), from,
                        battery.getBatteryNo(), swapOrder.getTakeBatteryId());
            }
            return;
        }
        SwapOrderEntity returnOrder = findBySeq(cabinet, commandSeq, OrderStatus.OPENED);
        if (returnOrder != null && OrderType.RETURN.name().equals(returnOrder.getOrderType())) {
            if (cas(returnOrder.getId(), OrderStatus.OPENED, OrderStatus.COMPLETED, w -> w
                    .set(SwapOrderEntity::getReturnTime, now)
                    .set(SwapOrderEntity::getCompleteTime, now)
                    .set(SwapOrderEntity::getReturnBatteryId, battery.getId()))) {
                returnOrder.setReturnTime(now);
                returnOrder.setCompleteTime(now);
                orderDelayService.cancelAll(returnOrder);
                billingService.charge(returnOrder, now);
                log.info("退租订单完成 orderNo={} 归还={}", returnOrder.getOrderNo(), battery.getBatteryNo());
            }
        }
    }

    private SwapOrderEntity findBySeq(CabinetEntity cabinet, Long commandSeq, OrderStatus status) {
        return orderDao.selectOne(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getCabinetId, cabinet.getId())
                .eq(SwapOrderEntity::getOpenCommandSeq, commandSeq)
                .eq(SwapOrderEntity::getStatus, status.getCode())
                .last("LIMIT 1"));
    }

    /** 借出电池绑定持有人（holder 唯一索引兜底"一人一电"） */
    private void assignHolder(BatteryEntity battery, Long userId) {
        int rows = batteryDao.update(null, new LambdaUpdateWrapper<BatteryEntity>()
                .eq(BatteryEntity::getId, battery.getId())
                .isNull(BatteryEntity::getHolderUserId)
                .set(BatteryEntity::getHolderUserId, userId)
                .set(BatteryEntity::getUpdateTime, System.currentTimeMillis()));
        if (rows == 0) {
            log.warn("借出持有人绑定未命中（可能已绑定/并发） batteryNo={} userId={}",
                    battery.getBatteryNo(), userId);
        }
    }

    private boolean cas(Long orderId, OrderStatus from, OrderStatus to,
                        Consumer<LambdaUpdateWrapper<SwapOrderEntity>> extra) {
        LambdaUpdateWrapper<SwapOrderEntity> wrapper = new LambdaUpdateWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getId, orderId)
                .eq(SwapOrderEntity::getStatus, from.getCode())
                .set(SwapOrderEntity::getStatus, to.getCode())
                .set(SwapOrderEntity::getUpdateTime, System.currentTimeMillis());
        extra.accept(wrapper);
        return orderDao.update(null, wrapper) > 0;
    }
}
