package com.swapops.server.order.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.common.RRException;
import com.swapops.server.config.BillingProperties;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.enums.OrderType;
import com.swapops.server.order.enums.PayType;
import com.swapops.server.order.enums.PaymentType;
import com.swapops.server.user.entity.UserPlanEntity;
import com.swapops.server.user.entity.WalletEntity;
import com.swapops.server.user.service.CouponService;
import com.swapops.server.user.service.PlanService;
import com.swapops.server.user.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 计费编排（S0.2 §4.4）：套餐扣次优先；无套餐走余额；TAKE 缴押金 / RETURN 退押金；
 * SWAP 超时按小时加收超时费（余额→押金依次抵扣，不足额记为欠费并告警——ORDER_ARREARS）。
 *
 * <p>资金动作全部落在 payment_record（{order_id,payment_type} 唯一键=幂等闸）；
 * 本方法在事件事务内执行：硬失败抛错 → 整个事件回滚（含台账/序守卫）→ 设备/消息重试幂等重入。</p>
 */
@Slf4j
@Service
public class BillingService {

    private final PlanService planService;
    private final WalletService walletService;
    private final PaymentRecordService paymentRecordService;
    private final SwapOrderDao orderDao;
    private final BillingProperties billingProperties;
    private final AlarmService alarmService;
    private final ArrearsService arrearsService;
    private final CouponService couponService;

    public BillingService(PlanService planService, WalletService walletService,
                          PaymentRecordService paymentRecordService, SwapOrderDao orderDao,
                          BillingProperties billingProperties, AlarmService alarmService,
                          ArrearsService arrearsService, CouponService couponService) {
        this.planService = planService;
        this.walletService = walletService;
        this.paymentRecordService = paymentRecordService;
        this.orderDao = orderDao;
        this.billingProperties = billingProperties;
        this.alarmService = alarmService;
        this.arrearsService = arrearsService;
        this.couponService = couponService;
    }

    /** 订单完成计费（调用方已完成订单 CAS 到终态） */
    public void charge(SwapOrderEntity order, long completeTime) {
        OrderType type = OrderType.valueOf(order.getOrderType());
        Long userId = order.getUserId();

        // 退租：不产生换电服务费，只退押金
        if (type == OrderType.RETURN) {
            int refunded = walletService.refundDeposit(userId);
            if (refunded > 0) {
                paymentRecordService.record(userId, order.getId(), PaymentType.DEPOSIT_REFUND, refunded, "押金退还");
            }
            orderDao.update(null, new LambdaUpdateWrapper<SwapOrderEntity>()
                    .eq(SwapOrderEntity::getId, order.getId())
                    .set(SwapOrderEntity::getFeeFen, 0)
                    .set(SwapOrderEntity::getUpdateTime, completeTime));
            order.setFeeFen(0);
            log.info("退租订单完成（无服务费，押金退还={}）orderNo={}", refunded, order.getOrderNo());
            return;
        }

        int baseFee = 0;
        int discount = 0;
        int chargedBase = 0;
        PayType payType;
        Long usedPlanId = null;
        UserPlanEntity plan = planService.findUsablePlan(userId, completeTime);
        boolean planCharged = false;
        if (plan != null) {
            if (plan.getRemainingTimes() == null) {
                // 月卡：有效期内即权益（日限在创建时已校验）
                planCharged = true;
            } else {
                // 次卡：CAS 扣次；并发扣穿则回落余额
                planCharged = planService.deductTimes(plan.getId());
            }
        }
        if (planCharged) {
            payType = PayType.PLAN;
            usedPlanId = plan.getId();
            paymentRecordService.record(userId, order.getId(), PaymentType.PLAN_DEDUCT, 0,
                    "套餐扣次 planId=" + plan.getPlanId());
            // 券仅余额计费单可核销：套餐单核销未发生 → 释放锁券（S7 WP-D）
            if (order.getCouponId() != null) {
                couponService.releaseLocked(order.getId());
            }
        } else {
            payType = PayType.BALANCE;
            baseFee = billingProperties.getBalanceFeeFen();
            // S7 WP-D：券核销（LOCKED→USED），抵扣不超过基础费
            discount = couponService.consumeForCharge(order.getId(), order.getCouponId(), baseFee);
            int payable = baseFee - discount;
            // S7 韧性补丁 G1：服务已交付（事件是事实源）——扣费失败不再回滚事件，落欠费由门槛拦截后续下单
            chargedBase = walletService.deductBalance(userId, payable) ? payable : 0;
            paymentRecordService.record(userId, order.getId(), PaymentType.BALANCE_FEE, chargedBase,
                    (discount > 0 ? "余额扣费（券抵扣 " + discount + " 分）" : "余额扣费")
                            + (chargedBase < payable ? "（欠费 " + (payable - chargedBase) + " 分）" : ""));
            if (discount > 0) {
                paymentRecordService.record(userId, order.getId(), PaymentType.COUPON_DEDUCT, discount,
                        "优惠券抵扣");
            }
            if (chargedBase < payable) {
                arrearsService.recordShortfall(userId, order.getId(), order.getOrderNo(),
                        payable - chargedBase, "BALANCE_FEE");
                alarmService.raise(AlarmService.DEVICE_ORDER, order.getOrderNo(), AlarmType.ORDER_ARREARS,
                        "基础费扣缴不足 userId=" + userId + " 应收=" + payable + " 实收=" + chargedBase);
            }
        }

        int overdueFee = 0;
        if (type == OrderType.SWAP && order.getTakeTime() != null) {
            long elapsed = completeTime - order.getTakeTime();
            long threshold = billingProperties.getOverdueHours() * 3600_000L;
            if (elapsed > threshold) {
                long extraHours = (elapsed - threshold + 3599_999L) / 3_600_000L;
                long amount = extraHours * billingProperties.getOverdueFeePerHourFen();
                overdueFee = chargeOverdue(userId, order.getId(), order.getOrderNo(),
                        (int) Math.min(Integer.MAX_VALUE, amount));
            }
        }

        if (type == OrderType.TAKE) {
            WalletEntity wallet = walletService.getByUserId(userId);
            if (wallet == null || wallet.getDepositFen() == null || wallet.getDepositFen() == 0) {
                int deposit = billingProperties.getDepositFen();
                // S7 韧性补丁 G1：押金不足同样不回滚——落欠费（DEPOSIT），由欠费门槛拦截后续下单
                if (walletService.moveBalanceToDeposit(userId, deposit)) {
                    paymentRecordService.record(userId, order.getId(), PaymentType.DEPOSIT, deposit, "押金缴纳");
                } else {
                    arrearsService.recordShortfall(userId, order.getId(), order.getOrderNo(),
                            deposit, "DEPOSIT");
                    alarmService.raise(AlarmService.DEVICE_ORDER, order.getOrderNo(), AlarmType.ORDER_ARREARS,
                            "押金扣缴不足 userId=" + userId + " 应收=" + deposit);
                }
            }
        }

        // 实收口径（S7 韧性补丁 G1）：feeFen=实际扣缴（基础费实收+超时费实收）；欠费部分在 arrears 单
        int actualFee = chargedBase + overdueFee;
        orderDao.update(null, new LambdaUpdateWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getId, order.getId())
                .set(SwapOrderEntity::getFeeFen, actualFee)
                .set(SwapOrderEntity::getDiscountFen, discount)
                .set(SwapOrderEntity::getPayType, payType.name())
                .set(SwapOrderEntity::getUserPlanId, usedPlanId)
                .set(SwapOrderEntity::getUpdateTime, completeTime));
        order.setFeeFen(actualFee);
        order.setDiscountFen(discount);
        order.setPayType(payType.name());
        order.setUserPlanId(usedPlanId);
        log.info("订单计费完成 orderNo={} type={} payType={} baseFee={} discount={} chargedBase={} overdueFee={}",
                order.getOrderNo(), type, payType, baseFee, discount, chargedBase, overdueFee);
    }

    /** 超时费：余额优先、押金兜底；不足额记欠费 + ORDER_ARREARS 告警（S5 审查补：欠费必须显性可见） */
    private int chargeOverdue(Long userId, Long orderId, String orderNo, int amount) {
        WalletEntity wallet = walletService.getByUserId(userId);
        int balance = wallet == null || wallet.getBalanceFen() == null ? 0 : wallet.getBalanceFen();
        int deposit = wallet == null || wallet.getDepositFen() == null ? 0 : wallet.getDepositFen();
        int charged = 0;
        int useBalance = Math.min(balance, amount);
        if (useBalance > 0 && walletService.deductBalance(userId, useBalance)) {
            charged += useBalance;
        }
        int rest = amount - charged;
        if (rest > 0) {
            int useDeposit = Math.min(deposit, rest);
            if (useDeposit > 0 && walletService.deductDeposit(userId, useDeposit)) {
                charged += useDeposit;
            }
        }
        paymentRecordService.record(userId, orderId, PaymentType.OVERDUE_FEE, charged,
                charged < amount ? "超时费（欠费 " + (amount - charged) + " 分）" : "超时费");
        if (charged < amount) {
            // S7 WP-D：欠费单闭环（同计费事务）；告警保留（结清自动关）
            arrearsService.recordShortfall(userId, orderId, orderNo, amount - charged, "OVERDUE_FEE");
            alarmService.raise(AlarmService.DEVICE_ORDER, orderNo, AlarmType.ORDER_ARREARS,
                    "超时费未足额 userId=" + userId + " 应收=" + amount + " 实收=" + charged
                            + " 欠费=" + (amount - charged));
            log.warn("超时费未足额（欠费单已落 + ORDER_ARREARS 告警）userId={} orderId={} 应收={} 实收={}",
                    userId, orderId, amount, charged);
        }
        return charged;
    }
}
