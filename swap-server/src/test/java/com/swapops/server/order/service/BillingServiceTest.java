package com.swapops.server.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.common.RRException;
import com.swapops.server.config.BillingProperties;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.enums.PaymentType;
import com.swapops.server.user.entity.UserPlanEntity;
import com.swapops.server.user.entity.WalletEntity;
import com.swapops.server.user.service.PlanService;
import com.swapops.server.user.service.WalletService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.isA;

/**
 * 计费单测：套餐扣次 / 余额扣费 / 押金缴纳退还 / 超时费 / 余额不足回滚。
 */
@DisplayName("计费编排")
@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock
    private PlanService planService;
    @Mock
    private WalletService walletService;
    @Mock
    private PaymentRecordService paymentRecordService;
    @Mock
    private SwapOrderDao orderDao;
    @Mock
    private AlarmService alarmService;

    @Mock
    private ArrearsService arrearsService;
    @Mock
    private com.swapops.server.user.service.CouponService couponService;

    @Mock
    private com.swapops.server.settlement.service.SettlementService settlementService;

    private BillingService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SwapOrderEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new BillingService(planService, walletService, paymentRecordService, orderDao,
                new BillingProperties(), alarmService, arrearsService, couponService, settlementService);
    }

    private SwapOrderEntity order(String type, Long takeTime) {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setOrderNo("SWO-1");
        order.setOrderType(type);
        order.setUserId(7L);
        order.setTakeTime(takeTime);
        return order;
    }

    private WalletEntity wallet(int balance, int deposit) {
        WalletEntity wallet = new WalletEntity();
        wallet.setUserId(7L);
        wallet.setBalanceFen(balance);
        wallet.setDepositFen(deposit);
        return wallet;
    }

    @Test
    @DisplayName("次卡权益：CAS 扣次，支付记录金额 0（权益扣减）")
    void 次卡扣次() {
        UserPlanEntity plan = new UserPlanEntity();
        plan.setId(5L);
        plan.setPlanId(100L);
        plan.setRemainingTimes(3);
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(plan);
        when(planService.deductTimes(5L)).thenReturn(true);

        service.charge(order("SWAP", System.currentTimeMillis()), System.currentTimeMillis());

        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.PLAN_DEDUCT), eq(0), anyString());
        verify(orderDao).update(isNull(), any());
    }

    @Test
    @DisplayName("无套餐：余额扣单次费")
    void 余额扣费() {
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(null);
        when(walletService.deductBalance(7L, 300)).thenReturn(true);
        service.charge(order("SWAP", null), System.currentTimeMillis());
        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.BALANCE_FEE), eq(300), anyString());
    }

    // ---------- 批次34：月卡日限（S0.1 声明的 dailyLimitTimes 落地） ----------

    /** 月卡（remainingTimes=null）；日限配置在套餐模板上 */
    private UserPlanEntity monthlyPlan() {
        UserPlanEntity plan = new UserPlanEntity();
        plan.setId(5L);
        plan.setPlanId(100L);
        plan.setRemainingTimes(null);
        return plan;
    }

    private com.swapops.server.user.entity.PlanEntity planTemplate(Integer dailyLimit) {
        com.swapops.server.user.entity.PlanEntity template = new com.swapops.server.user.entity.PlanEntity();
        template.setId(100L);
        template.setDailyLimitTimes(dailyLimit);
        return template;
    }

    @Test
    @DisplayName("月卡日限未满：仍走套餐权益（不加锁、不查单量）")
    void 月卡日限未满走套餐() {
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(monthlyPlan());
        when(planService.findPlan(100L)).thenReturn(planTemplate(5));
        when(orderDao.selectCount(any())).thenReturn(2L);

        service.charge(order("SWAP", null), System.currentTimeMillis());

        verify(planService).lockUserPlan(5L);
        verify(planService, never()).deductTimes(anyLong());
        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.PLAN_DEDUCT), eq(0), anyString());
        verify(paymentRecordService, never()).record(anyLong(), anyLong(), eq(PaymentType.BALANCE_FEE), anyInt(), anyString());
    }

    @Test
    @DisplayName("月卡日限已满：回落余额计费（权益不再放行），不再扣套餐")
    void 月卡日限已满回落余额() {
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(monthlyPlan());
        when(planService.findPlan(100L)).thenReturn(planTemplate(2));
        when(orderDao.selectCount(any())).thenReturn(2L);
        when(walletService.deductBalance(7L, 300)).thenReturn(true);

        service.charge(order("SWAP", null), System.currentTimeMillis());

        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.BALANCE_FEE), eq(300), anyString());
        verify(paymentRecordService, never()).record(anyLong(), anyLong(), eq(PaymentType.PLAN_DEDUCT), anyInt(), anyString());
        verify(planService).lockUserPlan(5L);
    }

    @Test
    @DisplayName("月卡未配日限（空/0）：不受限，且不产生行锁与计数查询")
    void 月卡未配日限不受限() {
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(monthlyPlan());
        when(planService.findPlan(100L)).thenReturn(planTemplate(null));

        service.charge(order("SWAP", null), System.currentTimeMillis());

        verify(planService, never()).lockUserPlan(anyLong());
        verify(orderDao, never()).selectCount(any());
        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.PLAN_DEDUCT), eq(0), anyString());
    }

    @Test
    @DisplayName("次卡不受日限影响：日限只约束月卡（次卡本身有次数闸）")
    void 次卡不受日限影响() {
        UserPlanEntity timesPlan = new UserPlanEntity();
        timesPlan.setId(6L);
        timesPlan.setPlanId(101L);
        timesPlan.setRemainingTimes(3);
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(timesPlan);
        when(planService.deductTimes(6L)).thenReturn(true);

        service.charge(order("SWAP", null), System.currentTimeMillis());

        verify(planService, never()).findPlan(anyLong());
        verify(orderDao, never()).selectCount(any());
        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.PLAN_DEDUCT), eq(0), anyString());
    }

    @Test
    @DisplayName("余额不足：不回滚事件（G1 韧性补丁）——实收 0 + 欠费单 + 告警")
    void 余额不足欠费化() {
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(null);
        when(walletService.deductBalance(7L, 300)).thenReturn(false);
        SwapOrderEntity order = order("SWAP", null);

        service.charge(order, System.currentTimeMillis());

        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.BALANCE_FEE), eq(0), anyString());
        verify(arrearsService).recordShortfall(eq(7L), eq(99L), eq("SWO-1"), eq(300), eq("BALANCE_FEE"));
        verify(alarmService).raise(eq(AlarmService.DEVICE_ORDER), eq("SWO-1"),
                eq(AlarmType.ORDER_ARREARS), anyString());
        assertThat(order.getFeeFen()).isZero();
    }

    @Test
    @DisplayName("首借：押金不足时余额转押金并记录")
    void 首借缴押金() {
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(null);
        when(walletService.deductBalance(7L, 300)).thenReturn(true);
        when(walletService.getByUserId(7L)).thenReturn(wallet(20000, 0));
        when(walletService.moveBalanceToDeposit(7L, 9900)).thenReturn(true);

        service.charge(order("TAKE", null), System.currentTimeMillis());

        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.DEPOSIT), eq(9900), anyString());
    }

    @Test
    @DisplayName("押金不足：不回滚事件（G1）——欠费单（DEPOSIT）+ 告警")
    void 押金不足欠费化() {
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(null);
        when(walletService.deductBalance(7L, 300)).thenReturn(true);
        when(walletService.getByUserId(7L)).thenReturn(wallet(200, 0));
        when(walletService.moveBalanceToDeposit(7L, 9900)).thenReturn(false);

        service.charge(order("TAKE", null), System.currentTimeMillis());

        verify(arrearsService).recordShortfall(eq(7L), eq(99L), eq("SWO-1"), eq(9900), eq("DEPOSIT"));
        verify(paymentRecordService, never()).record(eq(7L), eq(99L), eq(PaymentType.DEPOSIT),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    @DisplayName("退租：无服务费，只退押金")
    void 退租退押金() {
        when(walletService.refundDeposit(7L)).thenReturn(9900);
        SwapOrderEntity order = order("RETURN", null);

        service.charge(order, System.currentTimeMillis());

        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.DEPOSIT_REFUND), eq(9900), anyString());
        verify(planService, never()).findUsablePlan(any(), anyLong());
        assertThat(order.getFeeFen()).isZero();
    }

    @Test
    @DisplayName("SWAP 超时：超过阈值按小时计超时费（余额→押金）")
    void 超时费() {
        long now = System.currentTimeMillis();
        long takeTime = now - 26L * 3600 * 1000;
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(null);
        when(walletService.deductBalance(7L, 300)).thenReturn(true);
        when(walletService.getByUserId(7L)).thenReturn(wallet(5000, 9900));
        when(walletService.deductBalance(7L, 200)).thenReturn(true);

        service.charge(order("SWAP", takeTime), now);

        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.OVERDUE_FEE), eq(200), anyString());
    }

    @Test
    @DisplayName("用券：实收=基础费-抵扣，COUPON_DEDUCT 记补贴，feeFen 记实收（S7 WP-D）")
    void 用券抵扣() {
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(null);
        when(couponService.consumeForCharge(99L, 9L, 300)).thenReturn(100);
        when(walletService.deductBalance(7L, 200)).thenReturn(true);
        SwapOrderEntity order = order("SWAP", null);
        order.setCouponId(9L);

        service.charge(order, System.currentTimeMillis());

        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.BALANCE_FEE), eq(200), anyString());
        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.COUPON_DEDUCT), eq(100), anyString());
        assertThat(order.getFeeFen()).isEqualTo(200);
        assertThat(order.getDiscountFen()).isEqualTo(100);
        // S7 WP-B：计费末分账（同事务）
        verify(settlementService).settleOrder(order);
    }

    @Test
    @DisplayName("SWAP 超时费不足额：记欠费 + ORDER_ARREARS 告警（S5 审查补）")
    void 超时费欠费告警() {
        long now = System.currentTimeMillis();
        long takeTime = now - 26L * 3600 * 1000;
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(null);
        when(walletService.deductBalance(7L, 300)).thenReturn(true);
        when(walletService.getByUserId(7L)).thenReturn(wallet(100, 0)); // 余额/押金均不足付超时费

        service.charge(order("SWAP", takeTime), now);

        verify(paymentRecordService).record(eq(7L), eq(99L), eq(PaymentType.OVERDUE_FEE), eq(0), anyString());
        verify(alarmService).raise(eq(AlarmService.DEVICE_ORDER), eq("SWO-1"),
                eq(AlarmType.ORDER_ARREARS), anyString());
    }
}
