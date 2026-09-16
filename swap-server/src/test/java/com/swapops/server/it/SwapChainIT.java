package com.swapops.server.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.contract.BatteryStatus;
import com.swapops.contract.CellStatus;
import com.swapops.contract.EventType;
import com.swapops.contract.OrderStatus;
import com.swapops.server.device.config.SwapRedisKeys;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.form.DeviceEventForm;
import com.swapops.server.device.service.DeviceEventService;
import com.swapops.server.order.dao.PaymentRecordDao;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.PaymentRecordEntity;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.form.CreateOrderForm;
import com.swapops.server.order.service.SwapOrderService;
import com.swapops.server.user.dao.SwapUserDao;
import com.swapops.server.user.dao.UserPlanDao;
import com.swapops.server.user.dao.WalletDao;
import com.swapops.server.user.entity.SwapUserEntity;
import com.swapops.server.user.entity.UserPlanEntity;
import com.swapops.server.user.entity.WalletEntity;
import com.swapops.server.user.enums.UserPlanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-1 换电主链路（MySQL + Redis 容器，真实分配 Lua/计费/结算/事件序守卫）：
 * 首借 TAKE（下单→分配锁仓→门开→取电→完成计费）→ 退租 RETURN（下单空仓→门开→还电→完成退押金），
 * 全链断言设备台账 / 订单状态机 / 套餐扣次 / 支付流水 / 押金退还。
 */
@DisplayName("IT-1 换电主链路")
class SwapChainIT extends AbstractContainersIT {

    private static final String CABINET = "SWAP-C-001";
    private static final String BOOT_ID = "it1-boot";
    private static final String PHONE = "13800000002";

    @Autowired
    private SwapOrderService swapOrderService;
    @Autowired
    private DeviceEventService deviceEventService;
    @Autowired
    private SwapOrderDao orderDao;
    @Autowired
    private PaymentRecordDao paymentRecordDao;
    @Autowired
    private BatteryDao batteryDao;
    @Autowired
    private CellDao cellDao;
    @Autowired
    private SwapUserDao userDao;
    @Autowired
    private UserPlanDao userPlanDao;
    @Autowired
    private WalletDao walletDao;
    @Autowired
    private StringRedisTemplate redis;

    private SwapUserEntity user() {
        SwapUserEntity user = userDao.selectOne(new LambdaQueryWrapper<SwapUserEntity>()
                .eq(SwapUserEntity::getPhone, PHONE));
        assertThat(user).as("种子用户存在: " + PHONE).isNotNull();
        return user;
    }

    private UserPlanEntity activePlan(Long userId) {
        UserPlanEntity plan = userPlanDao.selectOne(new LambdaQueryWrapper<UserPlanEntity>()
                .eq(UserPlanEntity::getUserId, userId)
                .eq(UserPlanEntity::getStatus, UserPlanStatus.ACTIVE.getCode())
                .last("LIMIT 1"));
        assertThat(plan).as("用户有生效套餐").isNotNull();
        return plan;
    }

    private WalletEntity wallet(Long userId) {
        return walletDao.selectOne(new LambdaQueryWrapper<WalletEntity>()
                .eq(WalletEntity::getUserId, userId));
    }

    /** 模拟指令下发落 seq（IT 内不拉起 sim，指令台账销账路径由设备事件驱动覆盖） */
    private void bindSeq(SwapOrderEntity order, long seq) {
        orderDao.update(null, new LambdaUpdateWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getId, order.getId())
                .set(SwapOrderEntity::getOpenCommandSeq, seq)
                .set(SwapOrderEntity::getUpdateTime, System.currentTimeMillis()));
    }

    private boolean emit(EventType type, long eventSeq, Integer cellNo, String batteryNo,
                         Integer soc, Long commandSeq) {
        DeviceEventForm form = new DeviceEventForm();
        form.setCabinetNo(CABINET);
        form.setEventType(type.name());
        form.setBootId(BOOT_ID);
        form.setEventSeq(eventSeq);
        form.setCellNo(cellNo);
        form.setBatteryNo(batteryNo);
        form.setSoc(soc);
        form.setCommandSeq(commandSeq);
        return deviceEventService.handle(form);
    }

    @Test
    @DisplayName("首借→取电→退租→还电 全链（台账/计费/押金）")
    void swapChainEndToEnd() {
        SwapUserEntity user = user();
        UserPlanEntity planBefore = activePlan(user.getId());
        int timesBefore = planBefore.getRemainingTimes();
        int depositBefore = wallet(user.getId()).getDepositFen();

        // ---------- 1) 首借 TAKE：下单 + 分配锁仓 ----------
        CreateOrderForm form = new CreateOrderForm();
        form.setType("TAKE");
        form.setCabinetNo(CABINET);
        SwapOrderEntity take = swapOrderService.create(user.getId(), form, "it1-take-" + user.getId());
        assertThat(take.getStatus()).isEqualTo(OrderStatus.PENDING_OPEN.getCode());
        assertThat(take.getTakeBatteryId()).as("首借必须分配到满电电池").isNotNull();
        assertThat(take.getStationId()).isNotNull();

        CellEntity lockedCell = cellDao.selectById(take.getCellId());
        assertThat(lockedCell.getLockOrderId()).isEqualTo(take.getId());
        assertThat(lockedCell.getStatus()).isEqualTo(CellStatus.OCCUPIED.getCode());
        assertThat(redis.hasKey(SwapRedisKeys.CELL_LOCK_PREFIX + lockedCell.getId())).isTrue();
        assertThat(redis.opsForValue().get(SwapRedisKeys.PREEMPT_PREFIX + take.getOrderNo()))
                .isEqualTo(String.valueOf(lockedCell.getId()));

        // ---------- 2) 门开 → 取电（设备事件驱动订单完成） ----------
        long seq1 = 90001L;
        bindSeq(take, seq1);
        assertThat(emit(EventType.DOOR_OPENED, 1, lockedCell.getCellNo(), null, null, seq1)).isTrue();
        SwapOrderEntity opened = orderDao.selectById(take.getId());
        assertThat(opened.getStatus()).isEqualTo(OrderStatus.OPENED.getCode());

        BatteryEntity takeBattery = batteryDao.selectById(take.getTakeBatteryId());
        assertThat(emit(EventType.BATTERY_OUT, 2, lockedCell.getCellNo(),
                takeBattery.getBatteryNo(), null, seq1)).isTrue();

        SwapOrderEntity taken = orderDao.selectById(take.getId());
        assertThat(taken.getStatus()).isEqualTo(OrderStatus.COMPLETED.getCode());
        assertThat(taken.getTakeTime()).isNotNull();
        assertThat(taken.getCompleteTime()).isNotNull();

        BatteryEntity held = batteryDao.selectById(takeBattery.getId());
        assertThat(held.getStatus()).isEqualTo(BatteryStatus.LOANED.getCode());
        assertThat(held.getCellId()).isNull();
        assertThat(held.getHolderUserId()).isEqualTo(user.getId());

        CellEntity freed = cellDao.selectById(lockedCell.getId());
        assertThat(freed.getStatus()).isEqualTo(CellStatus.EMPTY.getCode());
        assertThat(freed.getBatteryId()).isNull();
        assertThat(freed.getLockOrderId()).isNull();
        assertThat(redis.hasKey(SwapRedisKeys.CELL_LOCK_PREFIX + lockedCell.getId())).isFalse();

        // 计费断言：套餐扣次（5→4）+ PLAN_DEDUCT 流水 + payType=PLAN
        UserPlanEntity planAfterTake = userPlanDao.selectById(planBefore.getId());
        assertThat(planAfterTake.getRemainingTimes()).isEqualTo(timesBefore - 1);
        assertThat(taken.getPayType()).isEqualTo("PLAN");
        List<PaymentRecordEntity> takePayments = paymentRecordDao.selectList(
                new LambdaQueryWrapper<PaymentRecordEntity>().eq(PaymentRecordEntity::getOrderId, take.getId()));
        assertThat(takePayments).extracting(PaymentRecordEntity::getPaymentType)
                .contains("PLAN_DEDUCT");

        // ---------- 3) 退租 RETURN：下单空仓 + 还电 ----------
        form.setType("RETURN");
        SwapOrderEntity ret = swapOrderService.create(user.getId(), form, "it1-return-" + user.getId());
        assertThat(ret.getStatus()).isEqualTo(OrderStatus.PENDING_OPEN.getCode());
        CellEntity returnCell = cellDao.selectById(ret.getCellId());
        assertThat(returnCell.getStatus()).as("退租分配到空仓").isEqualTo(CellStatus.EMPTY.getCode());
        assertThat(returnCell.getLockOrderId()).isEqualTo(ret.getId());

        long seq2 = 90002L;
        bindSeq(ret, seq2);
        assertThat(emit(EventType.DOOR_OPENED, 3, returnCell.getCellNo(), null, null, seq2)).isTrue();
        assertThat(emit(EventType.BATTERY_IN, 4, returnCell.getCellNo(),
                held.getBatteryNo(), 40, seq2)).isTrue();

        SwapOrderEntity returned = orderDao.selectById(ret.getId());
        assertThat(returned.getStatus()).isEqualTo(OrderStatus.COMPLETED.getCode());
        assertThat(returned.getReturnBatteryId()).isEqualTo(held.getId());
        assertThat(returned.getFeeFen()).as("退租无服务费").isZero();

        BatteryEntity back = batteryDao.selectById(held.getId());
        assertThat(back.getStatus()).isEqualTo(BatteryStatus.CHARGING.getCode());
        assertThat(back.getCellId()).isEqualTo(returnCell.getId());
        assertThat(back.getHolderUserId()).isNull();
        assertThat(back.getSoc()).as("还电事件携带 SOC 落库").isEqualTo(40);

        CellEntity occupied = cellDao.selectById(returnCell.getId());
        assertThat(occupied.getStatus()).isEqualTo(CellStatus.OCCUPIED.getCode());
        assertThat(occupied.getBatteryId()).isEqualTo(held.getId());

        // 押金退还：9900 → 0（种子用户已缴押金）
        assertThat(depositBefore).isEqualTo(9900);
        assertThat(wallet(user.getId()).getDepositFen()).isZero();
        List<PaymentRecordEntity> returnPayments = paymentRecordDao.selectList(
                new LambdaQueryWrapper<PaymentRecordEntity>().eq(PaymentRecordEntity::getOrderId, ret.getId()));
        assertThat(returnPayments).extracting(PaymentRecordEntity::getPaymentType)
                .contains("DEPOSIT_REFUND");

        // 套餐次卡不因退租消耗
        assertThat(userPlanDao.selectById(planBefore.getId()).getRemainingTimes()).isEqualTo(timesBefore - 1);
    }
}
