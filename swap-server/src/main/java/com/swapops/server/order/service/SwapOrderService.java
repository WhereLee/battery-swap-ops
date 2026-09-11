package com.swapops.server.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.contract.OrderStatus;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.asset.entity.StationEntity;
import com.swapops.server.common.RRException;
import com.swapops.server.common.filter.TraceIdFilter;
import com.swapops.server.common.utils.StringUtils;
import com.swapops.server.config.BillingProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import com.swapops.server.device.service.CommandDispatchService;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.enums.OrderType;
import com.swapops.server.order.form.CreateOrderForm;
import com.swapops.server.user.entity.UserPlanEntity;
import com.swapops.server.user.entity.WalletEntity;
import com.swapops.server.user.service.PlanService;
import com.swapops.server.user.service.UserAccountService;
import com.swapops.server.user.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 换电订单：下单（资格校验 + 幂等 + 分配预占）、开仓下发（事务外补偿）、取消、超时关闭、视图。
 *
 * <p>事务边界有意设计：`create` 只做校验/落单/分配（DB+Redis，短事务）；开仓 HTTP 下发在
 * 控制器提交后执行（`sendOpenCommand`），失败走补偿（释放预占 + 关闭订单）——不在数据库事务里占据网络等待。</p>
 */
@Slf4j
@Service
public class SwapOrderService {

    private static final List<Integer> ACTIVE_STATUSES = List.of(
            OrderStatus.PENDING_OPEN.getCode(), OrderStatus.OPENED.getCode(),
            OrderStatus.TAKEN.getCode(), OrderStatus.OVERDUE.getCode());

    private final SwapOrderDao orderDao;
    private final CabinetDao cabinetDao;
    private final CellDao cellDao;
    private final BatteryDao batteryDao;
    private final StationDao stationDao;
    private final UserAccountService userAccountService;
    private final WalletService walletService;
    private final PlanService planService;
    private final AllocationService allocationService;
    private final CommandDispatchService commandDispatchService;
    private final BillingProperties billingProperties;

    public SwapOrderService(SwapOrderDao orderDao, CabinetDao cabinetDao, CellDao cellDao,
                            BatteryDao batteryDao, StationDao stationDao,
                            UserAccountService userAccountService, WalletService walletService,
                            PlanService planService, AllocationService allocationService,
                            CommandDispatchService commandDispatchService, BillingProperties billingProperties) {
        this.orderDao = orderDao;
        this.cabinetDao = cabinetDao;
        this.cellDao = cellDao;
        this.batteryDao = batteryDao;
        this.stationDao = stationDao;
        this.userAccountService = userAccountService;
        this.walletService = walletService;
        this.planService = planService;
        this.allocationService = allocationService;
        this.commandDispatchService = commandDispatchService;
        this.billingProperties = billingProperties;
    }

    /**
     * 下单：资格校验 →（idemKey/活跃单 双层防重）→ 落单 → 分配预占。
     * 开仓指令下发由控制器在此事务提交后调用 {@link #sendOpenCommand(String)}。
     */
    @Transactional
    public SwapOrderEntity create(Long userId, CreateOrderForm form, String idemKey) {
        OrderType type = parseType(form.getType());
        SwapOrderEntity existing = byIdemKey(idemKey);
        if (existing != null) {
            return existing;
        }
        userAccountService.requireActive(userId);

        SwapOrderEntity active = orderDao.selectOne(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getUserId, userId)
                .in(SwapOrderEntity::getStatus, ACTIVE_STATUSES)
                .last("LIMIT 1"));
        if (active != null) {
            throw new RRException("存在进行中的订单: " + active.getOrderNo());
        }

        BatteryEntity holder = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                .eq(BatteryEntity::getHolderUserId, userId));
        if (type == OrderType.TAKE && holder != null) {
            throw new RRException("已持有电池，" + holder.getBatteryNo() + "；请使用换电(SWAP)或退租(RETURN)");
        }
        if (type != OrderType.TAKE && holder == null) {
            throw new RRException(type == OrderType.SWAP ? "未持有电池，请使用首借(TAKE)" : "未持有电池，无需退租");
        }

        long now = System.currentTimeMillis();
        if (type != OrderType.RETURN) {
            // 退租不收服务费：不校验套餐/余额；TAKE 需保证将来缴得起押金
            WalletEntity wallet = walletService.getByUserId(userId);
            int balance = wallet == null || wallet.getBalanceFen() == null ? 0 : wallet.getBalanceFen();
            int deposit = wallet == null || wallet.getDepositFen() == null ? 0 : wallet.getDepositFen();
            UserPlanEntity usablePlan = planService.findUsablePlan(userId, now);
            if (usablePlan == null && balance < billingProperties.getBalanceFeeFen()) {
                throw new RRException("无可用套餐且余额不足（单次 " + billingProperties.getBalanceFeeFen() + " 分）");
            }
            if (type == OrderType.TAKE && deposit == 0 && balance < billingProperties.getDepositFen()) {
                throw new RRException("押金不足（需 " + billingProperties.getDepositFen() + " 分）：请先充值或购买套餐");
            }
        }

        SwapOrderEntity order = new SwapOrderEntity();
        order.setOrderNo(generateOrderNo());
        order.setOrderType(type.name());
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_OPEN.getCode());
        order.setIdemKey(idemKey);
        order.setFeeFen(0);
        order.setCreateTime(now);
        order.setUpdateTime(now);
        order.setPreemptExpireTime(now + billingProperties.getPreemptTtlSeconds() * 1000L);
        try {
            orderDao.insert(order);
        } catch (DuplicateKeyException e) {
            SwapOrderEntity raced = byIdemKey(idemKey);
            if (raced != null) {
                return raced;
            }
            // active_user_key 唯一冲突 = 并发同用户下单
            throw new RRException("存在进行中的订单，请勿重复下单");
        }

        boolean fullPath = type != OrderType.RETURN;
        AllocationService.AllocResult alloc = allocateFirstAvailable(form, order, fullPath);
        CabinetEntity cabinet = cabinetDao.selectById(alloc.cell().getCabinetId());
        order.setCabinetId(cabinet.getId());
        order.setStationId(cabinet.getStationId());
        order.setCellId(alloc.cell().getId());
        order.setTakeBatteryId(alloc.battery() == null ? null : alloc.battery().getId());
        order.setUpdateTime(System.currentTimeMillis());
        orderDao.updateById(order);
        log.info("下单成功 orderNo={} type={} userId={} cabinetNo={} cellNo={} batteryNo={}",
                order.getOrderNo(), type, userId, cabinet.getCabinetNo(), alloc.cell().getCellNo(),
                alloc.battery() == null ? "-" : alloc.battery().getBatteryNo());
        return order;
    }

    /** 开仓指令下发（事务外）：准备 seq → 绑定订单 → HTTP 下发；失败补偿（释放预占 + 关闭订单） */
    public void sendOpenCommand(String orderNo) {
        SwapOrderEntity order = orderDao.selectOne(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getOrderNo, orderNo));
        if (order == null) {
            throw new RRException("订单不存在: " + orderNo);
        }
        if (order.getStatus() != OrderStatus.PENDING_OPEN.getCode()) {
            log.info("订单已推进，跳过开仓下发 orderNo={} status={}", orderNo, order.getStatus());
            return;
        }
        CabinetEntity cabinet = order.getCabinetId() == null ? null : cabinetDao.selectById(order.getCabinetId());
        CellEntity cell = order.getCellId() == null ? null : cellDao.selectById(order.getCellId());
        if (cabinet == null || cell == null) {
            throw new RRException("订单目标柜/仓数据缺失: " + orderNo);
        }
        CommandLogEntity cmdLog = commandDispatchService.prepareOpen(cabinet.getCabinetNo(),
                cell.getCellNo(), TraceIdFilter.currentOrGenerate());
        order.setOpenCommandSeq(cmdLog.getCommandSeq());
        order.setUpdateTime(System.currentTimeMillis());
        orderDao.updateById(order);
        try {
            commandDispatchService.dispatchPrepared(cmdLog, cabinet.getCabinetNo(), cell.getCellNo());
        } catch (RRException e) {
            allocationService.release(order.getCellId(), order.getId(), order.getOrderNo());
            cas(order.getId(), OrderStatus.PENDING_OPEN, OrderStatus.CANCELLED,
                    w -> w.set(SwapOrderEntity::getCancelTime, System.currentTimeMillis())
                            .set(SwapOrderEntity::getCloseReason, "SEND_FAILED"));
            log.warn("开仓指令下发失败，订单已补偿关闭 orderNo={} cause={}", orderNo, e.getMessage());
            throw e;
        }
    }

    /** 用户取消（仅未开仓；终态幂等返回） */
    public SwapOrderEntity cancel(String orderNo, Long userId) {
        SwapOrderEntity order = requireOwn(orderNo, userId);
        if (isTerminal(order.getStatus())) {
            return order;
        }
        if (order.getStatus() == OrderStatus.PENDING_OPEN.getCode()) {
            boolean closed = cas(order.getId(), OrderStatus.PENDING_OPEN, OrderStatus.CANCELLED,
                    w -> w.set(SwapOrderEntity::getCancelTime, System.currentTimeMillis())
                            .set(SwapOrderEntity::getCloseReason, "USER_CANCEL"));
            if (closed) {
                allocationService.release(order.getCellId(), order.getId(), order.getOrderNo());
                log.info("订单已取消 orderNo={}", orderNo);
            }
        } else {
            throw new RRException("当前状态不可取消: " + OrderStatus.fromCode(order.getStatus()));
        }
        return orderDao.selectById(order.getId());
    }

    /** 按柜+指令 seq 定位活跃订单（对账路径） */
    public SwapOrderEntity findByOpenCommand(String cabinetNo, long commandSeq) {
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo));
        if (cabinet == null) {
            return null;
        }
        return orderDao.selectOne(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getCabinetId, cabinet.getId())
                .eq(SwapOrderEntity::getOpenCommandSeq, commandSeq)
                .in(SwapOrderEntity::getStatus, ACTIVE_STATUSES)
                .orderByDesc(SwapOrderEntity::getId)
                .last("LIMIT 1"));
    }

    /** 对账证据推进：设备已执行开仓（lastCommandSeq≥seq）且仓未取 → PENDING_OPEN→OPENED */
    public boolean markOpenedByEvidence(SwapOrderEntity order) {
        return cas(order.getId(), OrderStatus.PENDING_OPEN, OrderStatus.OPENED,
                w -> w.set(SwapOrderEntity::getOpenTime, System.currentTimeMillis()));
    }

    /** 异常终止（设备故障/证据丢失）：任意活跃态 → EXCEPTION + 释放预占（交人工处置） */
    public boolean markException(SwapOrderEntity order, String reason) {
        OrderStatus from = OrderStatus.fromCode(order.getStatus());
        if (isTerminal(order.getStatus())) {
            return false;
        }
        boolean marked = cas(order.getId(), from, OrderStatus.EXCEPTION,
                w -> w.set(SwapOrderEntity::getCancelTime, System.currentTimeMillis())
                        .set(SwapOrderEntity::getCloseReason, reason));
        if (marked) {
            allocationService.release(order.getCellId(), order.getId(), order.getOrderNo());
            log.warn("订单转人工异常 orderNo={} reason={}", order.getOrderNo(), reason);
        }
        return marked;
    }

    /** 超时关闭（扫描任务调用）：任意活跃态 → TIMEOUT_CLOSED + 释放预占 */
    public boolean closeTimedOut(SwapOrderEntity order, String reason) {
        OrderStatus from = OrderStatus.fromCode(order.getStatus());
        boolean closed = cas(order.getId(), from, OrderStatus.TIMEOUT_CLOSED,
                w -> w.set(SwapOrderEntity::getCancelTime, System.currentTimeMillis())
                        .set(SwapOrderEntity::getCloseReason, reason));
        if (closed) {
            allocationService.release(order.getCellId(), order.getId(), order.getOrderNo());
            log.warn("订单超时关闭 orderNo={} from={} reason={}", order.getOrderNo(), from, reason);
        }
        return closed;
    }

    /** 管理端订单分页（A6；只读运营视角） */
    public com.swapops.server.common.utils.PageResult<SwapOrderEntity> pageOrders(
            Integer page, Integer limit, Long userId, Integer status, String orderNo) {
        int pageNum = com.swapops.server.common.utils.PageParams.page(page);
        int size = com.swapops.server.common.utils.PageParams.limit(limit);
        com.baomidou.mybatisplus.core.metadata.IPage<SwapOrderEntity> result = orderDao.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNum, size),
                new LambdaQueryWrapper<SwapOrderEntity>()
                        .eq(userId != null, SwapOrderEntity::getUserId, userId)
                        .eq(status != null, SwapOrderEntity::getStatus, status)
                        .like(StringUtils.isNotBlank(orderNo), SwapOrderEntity::getOrderNo, orderNo)
                        .orderByDesc(SwapOrderEntity::getCreateTime));
        return com.swapops.server.common.utils.PageResult.of(result);
    }

    public SwapOrderEntity requireOwn(String orderNo, Long userId) {
        SwapOrderEntity order = orderDao.selectOne(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getOrderNo, orderNo));
        if (order == null || !order.getUserId().equals(userId)) {
            throw new RRException("订单不存在或无权访问: " + orderNo);
        }
        return order;
    }

    /** 订单视图（详情/列表共用） */
    public Map<String, Object> view(SwapOrderEntity order) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderNo", order.getOrderNo());
        view.put("type", order.getOrderType());
        view.put("status", order.getStatus());
        view.put("statusDesc", OrderStatus.fromCode(order.getStatus()).name());
        view.put("feeFen", order.getFeeFen());
        view.put("payType", order.getPayType());
        view.put("preemptExpireTime", order.getPreemptExpireTime());
        view.put("createTime", order.getCreateTime());
        view.put("openTime", order.getOpenTime());
        view.put("takeTime", order.getTakeTime());
        view.put("returnTime", order.getReturnTime());
        view.put("completeTime", order.getCompleteTime());
        view.put("closeReason", order.getCloseReason());
        CabinetEntity cabinet = order.getCabinetId() == null ? null : cabinetDao.selectById(order.getCabinetId());
        view.put("cabinetNo", cabinet == null ? null : cabinet.getCabinetNo());
        CellEntity cell = order.getCellId() == null ? null : cellDao.selectById(order.getCellId());
        view.put("cellNo", cell == null ? null : cell.getCellNo());
        BatteryEntity takeBattery = order.getTakeBatteryId() == null ? null
                : batteryDao.selectById(order.getTakeBatteryId());
        view.put("takeBatteryNo", takeBattery == null ? null : takeBattery.getBatteryNo());
        BatteryEntity returnBattery = order.getReturnBatteryId() == null ? null
                : batteryDao.selectById(order.getReturnBatteryId());
        view.put("returnBatteryNo", returnBattery == null ? null : returnBattery.getBatteryNo());
        return view;
    }

    private AllocationService.AllocResult allocateFirstAvailable(CreateOrderForm form, SwapOrderEntity order,
                                                                 boolean fullPath) {
        List<CabinetEntity> candidates = new ArrayList<>();
        if (StringUtils.isNotBlank(form.getCabinetNo())) {
            CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                    .eq(CabinetEntity::getCabinetNo, form.getCabinetNo()));
            if (cabinet == null) {
                throw new RRException("柜不存在: " + form.getCabinetNo());
            }
            candidates.add(cabinet);
        } else if (StringUtils.isNotBlank(form.getStationNo())) {
            StationEntity station = stationDao.selectOne(new LambdaQueryWrapper<StationEntity>()
                    .eq(StationEntity::getStationNo, form.getStationNo()));
            if (station == null) {
                throw new RRException("站点不存在: " + form.getStationNo());
            }
            if (station.getStatus() == null || station.getStatus() != 1) {
                throw new RRException("站点已停用: " + form.getStationNo());
            }
            candidates.addAll(cabinetDao.selectList(new LambdaQueryWrapper<CabinetEntity>()
                    .eq(CabinetEntity::getStationId, station.getId())
                    .orderByAsc(CabinetEntity::getId)));
        } else {
            candidates.addAll(cabinetDao.selectList(new LambdaQueryWrapper<CabinetEntity>()
                    .orderByAsc(CabinetEntity::getId)));
        }
        if (candidates.isEmpty()) {
            throw new RRException("暂无可用的换电柜");
        }
        RRException last = null;
        for (CabinetEntity cabinet : candidates) {
            try {
                return allocationService.allocate(cabinet.getCabinetNo(), order.getId(), order.getOrderNo(), fullPath);
            } catch (RRException e) {
                last = e;
            }
        }
        throw last == null ? new RRException("暂无可换资源，请稍后重试") : last;
    }

    private SwapOrderEntity byIdemKey(String idemKey) {
        return orderDao.selectOne(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getIdemKey, idemKey));
    }

    private OrderType parseType(String raw) {
        try {
            return OrderType.valueOf(raw == null ? "" : raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RRException("订单类型非法: " + raw + "（可选 SWAP/TAKE/RETURN）");
        }
    }

    private String generateOrderNo() {
        return "SW" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    private boolean isTerminal(Integer status) {
        return status == OrderStatus.COMPLETED.getCode() || status == OrderStatus.CANCELLED.getCode()
                || status == OrderStatus.TIMEOUT_CLOSED.getCode() || status == OrderStatus.EXCEPTION.getCode();
    }

    private boolean cas(Long orderId, OrderStatus from, OrderStatus to,
                        java.util.function.Consumer<LambdaUpdateWrapper<SwapOrderEntity>> extra) {
        LambdaUpdateWrapper<SwapOrderEntity> wrapper = new LambdaUpdateWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getId, orderId)
                .eq(SwapOrderEntity::getStatus, from.getCode())
                .set(SwapOrderEntity::getStatus, to.getCode())
                .set(SwapOrderEntity::getUpdateTime, System.currentTimeMillis());
        extra.accept(wrapper);
        return orderDao.update(null, wrapper) > 0;
    }
}
