package com.swapops.server.order.service.pay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.server.common.delay.DelayQueueService;
import com.swapops.server.common.id.SnowflakeIdGenerator;
import com.swapops.server.common.retry.DeadlockRetryExecutor;
import com.swapops.server.order.dao.RefundRecordDao;
import com.swapops.server.order.entity.PaymentRecordEntity;
import com.swapops.server.order.entity.RefundRecordEntity;
import com.swapops.server.order.enums.PaymentType;
import com.swapops.server.order.enums.RefundStatus;
import com.swapops.server.order.service.PaymentRecordService;
import com.swapops.server.user.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 退款服务（S3.4）：幂等闸 = unique(order_id, reason) + CAS WAIT→SUCCESS。
 * 执行失败留 WAIT 并延迟重投（refund-apply），超限进死信（S3.6 告警）。
 * 退款统一退入钱包余额（MOCK 渠道；真实渠道对接时替换 RefundChannelClient）。
 */
@Slf4j
@Service
public class RefundService {

    /**
     * 计入"可退"的收费类型（订单维度）。
     * 注意：押金（DEPOSIT）**不在**可退范围——押金退还只走退租（RETURN）流程的 refundDeposit，
     * 若计入将被管理端人工退款二次退还（S7 WP-0 审查发现的资金缺陷）。
     */
    private static final Set<String> REFUNDABLE_TYPES = Set.of(
            PaymentType.BALANCE_FEE.name(), PaymentType.OVERDUE_FEE.name());

    /** 延迟重试主题 */
    public static final String REFUND_TOPIC = "refund-apply";

    private final RefundRecordDao refundRecordDao;
    private final com.swapops.server.order.dao.PaymentRecordDao paymentRecordDao;
    private final PaymentRecordService paymentRecordService;
    private final WalletService walletService;
    private final DelayQueueService delayQueueService;
    private final SnowflakeIdGenerator idGenerator;
    private final DeadlockRetryExecutor deadlockRetryExecutor;

    public RefundService(RefundRecordDao refundRecordDao,
                         com.swapops.server.order.dao.PaymentRecordDao paymentRecordDao,
                         PaymentRecordService paymentRecordService, WalletService walletService,
                         DelayQueueService delayQueueService, SnowflakeIdGenerator idGenerator,
                         DeadlockRetryExecutor deadlockRetryExecutor) {
        this.refundRecordDao = refundRecordDao;
        this.paymentRecordDao = paymentRecordDao;
        this.paymentRecordService = paymentRecordService;
        this.walletService = walletService;
        this.delayQueueService = delayQueueService;
        this.idGenerator = idGenerator;
        this.deadlockRetryExecutor = deadlockRetryExecutor;
    }

    /**
     * 发起退款（幂等）：同 (orderId, reason) 重复调用直接返回既有记录，不二次入账。
     * 金额上限=可退金额（已收未退）；超额直接拒绝（防人工超额/双通道双退）。
     * 执行异常不抛出：记日志 + 延迟重投（补偿任务/人工可反复触达）。
     */
    public RefundRecordEntity refund(Long orderId, Long userId, int amountFen, String reason) {
        int refundable = refundableAmount(orderId);
        if (amountFen > refundable) {
            throw new com.swapops.server.common.RRException(
                    "退款金额超过可退金额（可退 " + refundable + " 分）: " + amountFen);
        }
        // 死锁重试（插入/流水更新可能与其他补偿并发触发 1213；动作本身幂等，重试安全）
        return deadlockRetryExecutor.execute(() -> doRefund(orderId, userId, amountFen, reason));
    }

    private RefundRecordEntity doRefund(Long orderId, Long userId, int amountFen, String reason) {
        RefundRecordEntity existing = findByOrderReason(orderId, reason);
        if (existing != null) {
            return existing;
        }
        long now = System.currentTimeMillis();
        RefundRecordEntity record = new RefundRecordEntity();
        record.setRefundNo("RF" + idGenerator.nextIdString());
        record.setOrderId(orderId);
        record.setUserId(userId);
        record.setAmountFen(amountFen);
        record.setReason(reason);
        record.setStatus(RefundStatus.WAIT.name());
        // S7 WP-A：人工退款操作人（补偿通道线程无管理上下文 → null）
        record.setOperatorId(com.swapops.server.admin.security.AdminContext.currentAdminId());
        record.setOperatorName(com.swapops.server.admin.security.AdminContext.currentUsername());
        record.setCreateTime(now);
        record.setUpdateTime(now);
        try {
            refundRecordDao.insert(record);
        } catch (DuplicateKeyException e) {
            return findByOrderReason(orderId, reason); // 并发重复：幂等返回
        }
        try {
            apply(record);
        } catch (RuntimeException e) {
            log.error("退款执行失败，转入延迟重试 orderId={} refundNo={} amountFen={} cause={}",
                    orderId, record.getRefundNo(), amountFen, e.getMessage());
            enqueueRetry(record.getRefundNo());
        }
        return refundRecordDao.selectById(record.getId());
    }

    /** 执行退款（幂等）：仅 WAIT 执行；金额>0 时入余额 + 记 REFUND 流水，最后 CAS 标记 SUCCESS */
    @Transactional
    public void apply(RefundRecordEntity record) {
        if (!RefundStatus.WAIT.name().equals(record.getStatus())) {
            return;
        }
        if (record.getAmountFen() != null && record.getAmountFen() > 0) {
            walletService.addBalance(record.getUserId(), record.getAmountFen());
            paymentRecordService.record(record.getUserId(), record.getOrderId(), PaymentType.REFUND,
                    record.getAmountFen(), "退款:" + record.getReason());
        }
        int rows = refundRecordDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RefundRecordEntity>()
                .eq(RefundRecordEntity::getId, record.getId())
                .eq(RefundRecordEntity::getStatus, RefundStatus.WAIT.name())
                .set(RefundRecordEntity::getStatus, RefundStatus.SUCCESS.name())
                .set(RefundRecordEntity::getUpdateTime, System.currentTimeMillis()));
        if (rows == 0) {
            throw new IllegalStateException("退款状态 CAS 冲突 refundNo=" + record.getRefundNo());
        }
        log.info("退款成功 refundNo={} orderId={} amountFen={} reason={}",
                record.getRefundNo(), record.getOrderId(), record.getAmountFen(), record.getReason());
    }

    public RefundRecordEntity findByRefundNo(String refundNo) {
        return refundRecordDao.selectOne(new LambdaQueryWrapper<RefundRecordEntity>()
                .eq(RefundRecordEntity::getRefundNo, refundNo));
    }

    /** 可退金额 = 该订单已收且尚未退的（基础费/押金/超时费）之和（已存在的退款单 WAIT/SUCCESS 均计入已退） */
    public int refundableAmount(Long orderId) {
        List<PaymentRecordEntity> records = paymentRecordDao.selectList(
                new LambdaQueryWrapper<PaymentRecordEntity>().eq(PaymentRecordEntity::getOrderId, orderId));
        int sum = 0;
        for (PaymentRecordEntity record : records) {
            if (REFUNDABLE_TYPES.contains(record.getPaymentType())
                    && record.getAmountFen() != null && record.getAmountFen() > 0) {
                sum += record.getAmountFen();
            }
        }
        List<RefundRecordEntity> refunds = refundRecordDao.selectList(
                new LambdaQueryWrapper<RefundRecordEntity>().eq(RefundRecordEntity::getOrderId, orderId));
        for (RefundRecordEntity refund : refunds) {
            if (refund.getAmountFen() != null && refund.getAmountFen() > 0) {
                sum -= refund.getAmountFen();
            }
        }
        return Math.max(sum, 0);
    }

    private RefundRecordEntity findByOrderReason(Long orderId, String reason) {
        if (orderId == null) {
            return null;
        }
        return refundRecordDao.selectOne(new LambdaQueryWrapper<RefundRecordEntity>()
                .eq(RefundRecordEntity::getOrderId, orderId)
                .eq(RefundRecordEntity::getReason, reason));
    }

    private void enqueueRetry(String refundNo) {
        try {
            delayQueueService.enqueue(REFUND_TOPIC, refundNo, refundNo,
                    System.currentTimeMillis() + 10_000L);
        } catch (RuntimeException e) {
            log.error("退款延迟重投登记失败（补偿任务将兜底） refundNo={} cause={}", refundNo, e.getMessage());
        }
    }
}
