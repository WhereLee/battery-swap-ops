package com.swapops.server.order.service.pay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 退款服务（S3.4）：幂等闸 = unique(order_id, reason) + CAS WAIT→SUCCESS。
 * 执行失败留 WAIT 并延迟重投（refund-apply），超限进死信（S3.6 告警）；
 * <b>WAIT 不是终局</b>——再次发起（管理端/补偿任务/延迟重投）会重驱动同一张单，见 {@link #apply}。
 * 退款统一退入钱包余额（MOCK 渠道；真实渠道对接时替换 RefundChannelClient）。
 *
 * <p><b>事务边界</b>：资金动作与状态 CAS 同事务（编程式事务），站内信与分账冲正在事务外 best-effort。
 * 这个边界的由来是一次真实缺陷（批次32）：{@code @Transactional} 加在被同类私有方法调用的
 * {@code apply} 上，自调用绕过代理 ⇒ 注解静默失效 ⇒ 加款已提交而 CAS 未做，重驱动时二次入账。
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
    private final com.swapops.server.user.service.UserMessageService messageService;
    private final com.swapops.server.settlement.service.SettlementService settlementService;
    private final com.swapops.server.order.dao.SwapOrderDao swapOrderDao;

    /** 退款资金动作的事务执行器（见 {@link #apply} 的事务边界说明）。 */
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public RefundService(RefundRecordDao refundRecordDao,
                         com.swapops.server.order.dao.PaymentRecordDao paymentRecordDao,
                         PaymentRecordService paymentRecordService, WalletService walletService,
                         DelayQueueService delayQueueService, SnowflakeIdGenerator idGenerator,
                         DeadlockRetryExecutor deadlockRetryExecutor,
                         com.swapops.server.user.service.UserMessageService messageService,
                         com.swapops.server.settlement.service.SettlementService settlementService,
                         com.swapops.server.order.dao.SwapOrderDao swapOrderDao,
                         org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.refundRecordDao = refundRecordDao;
        this.paymentRecordDao = paymentRecordDao;
        this.paymentRecordService = paymentRecordService;
        this.walletService = walletService;
        this.delayQueueService = delayQueueService;
        this.idGenerator = idGenerator;
        this.deadlockRetryExecutor = deadlockRetryExecutor;
        this.messageService = messageService;
        this.settlementService = settlementService;
        this.swapOrderDao = swapOrderDao;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
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
            // 命中既有单不再直接返回：WAIT 表示上一轮"资金动作 + CAS"没有整段落定（进程被杀、
            // 死锁、下游异常），单子还悬着——用户没拿到钱而 refundableAmount 已把它算作"已退"。
            // 这里显式重驱动（apply 幂等且同事务），重试耗尽/管理端重复发起都能自愈。
            if (RefundStatus.WAIT.name().equals(existing.getStatus())) {
                log.warn("退款单处于 WAIT，重驱动执行 refundNo={} orderId={}", existing.getRefundNo(), orderId);
                applyAndScheduleRetryOnFailure(existing);
                return refundRecordDao.selectById(existing.getId());
            }
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
        applyAndScheduleRetryOnFailure(record);
        return refundRecordDao.selectById(record.getId());
    }

    /** 执行退款，失败不抛出：记日志 + 延迟重投（补偿任务/人工可反复触达，单子停在 WAIT 可自愈）。 */
    private void applyAndScheduleRetryOnFailure(RefundRecordEntity record) {
        try {
            apply(record);
        } catch (RuntimeException e) {
            log.error("退款执行失败，转入延迟重试 orderId={} refundNo={} amountFen={} cause={}",
                    record.getOrderId(), record.getRefundNo(), record.getAmountFen(), e.getMessage());
            enqueueRetry(record.getRefundNo());
        }
    }

    /**
     * 执行退款（幂等）：仅 WAIT 执行；金额 &gt; 0 时入余额 + 记 REFUND 流水，最后 CAS 标记 SUCCESS。
     *
     * <p><b>事务边界（本方法存在的理由）</b>：资金动作（入余额 + 记 REFUND 流水）与
     * {@code WAIT→SUCCESS} 的 CAS <b>必须同一事务</b>——CAS 就是"这笔钱只出一次"的闸门，
     * 如果加款先自动提交、CAS 再单独执行，那么两步之间任何失败都会留下"钱已出、单仍 WAIT"，
     * 而 WAIT 单会被重驱动（延迟重投／补偿任务／管理端重复发起）再次加款 ⇒ <b>同一张单退两次钱</b>。
     *
     * <p>为什么用编程式事务而不是 {@code @Transactional}：本方法会被同类私有方法 {@code doRefund} 直接调用，
     * 而 <b>自调用不经过 Spring 代理，注解会静默失效</b>（不报错、不告警，只是没有事务）。
     * 编程式事务对"谁来调"不敏感，在线退款与延迟重投两条入口语义完全一致。
     * 这条纪律由 {@code TransactionalSelfInvocationGuardTest} 在字节码层兜底。
     *
     * <p>站内信与分账冲正刻意留在事务<b>外</b>：它们是通知/台账补偿，失败不应回滚已成立的退款
     * （回到"事务里"会让一条站内信写失败把退款一起回滚，与 S7 WP-D 的声明相反）。
     */
    public void apply(RefundRecordEntity record) {
        if (!RefundStatus.WAIT.name().equals(record.getStatus())) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> applyMoney(record));
        afterApply(record);
    }

    /** 事务内：加款 + 记流水 + CAS。任何一步抛异常都整体回滚，退款单保持 WAIT（可被重驱动）。 */
    private void applyMoney(RefundRecordEntity record) {
        if (record.getAmountFen() != null && record.getAmountFen() > 0) {
            walletService.addBalance(record.getUserId(), record.getAmountFen());
            paymentRecordService.record(record.getUserId(), record.getOrderId(), PaymentType.REFUND,
                    record.getAmountFen(), "退款:" + record.getReason());
        }
        int rows = refundRecordDao.update(null, new LambdaUpdateWrapper<RefundRecordEntity>()
                .eq(RefundRecordEntity::getId, record.getId())
                .eq(RefundRecordEntity::getStatus, RefundStatus.WAIT.name())
                .set(RefundRecordEntity::getStatus, RefundStatus.SUCCESS.name())
                .set(RefundRecordEntity::getUpdateTime, System.currentTimeMillis()));
        if (rows == 0) {
            throw new IllegalStateException("退款状态 CAS 冲突 refundNo=" + record.getRefundNo());
        }
    }

    /** 事务外：到账通知 + 分账冲正，各自 best-effort（失败只记日志，不影响退款成立）。 */
    private void afterApply(RefundRecordEntity record) {
        if (record.getAmountFen() != null && record.getAmountFen() > 0) {
            // S7 WP-D：退款到账站内信（写失败不影响退款）
            try {
                messageService.send(record.getUserId(), "REFUND", "退款到账",
                        "退款 " + record.getAmountFen() + " 分已入余额（" + record.getReason() + "）");
            } catch (RuntimeException e) {
                log.warn("[退款] 到账站内信写入异常（不阻断退款） refundNo={} cause={}",
                        record.getRefundNo(), e.getMessage());
            }
        }
        // S7 WP-B：退款冲正（仅对已分账的完成单；event_key 幂等，失败不阻断退款）
        try {
            if (record.getOrderId() != null) {
                settlementService.recordRefundReversal(swapOrderDao.selectById(record.getOrderId()), record);
            }
        } catch (RuntimeException e) {
            log.warn("[退款] 冲正流水写入异常（不阻断退款） refundNo={} cause={}",
                    record.getRefundNo(), e.getMessage());
        }
        log.info("退款成功 refundNo={} orderId={} amountFen={} reason={}",
                record.getRefundNo(), record.getOrderId(), record.getAmountFen(), record.getReason());
    }

    public RefundRecordEntity findByRefundNo(String refundNo) {
        return refundRecordDao.selectOne(new LambdaQueryWrapper<RefundRecordEntity>()
                .eq(RefundRecordEntity::getRefundNo, refundNo));
    }

    /** 可退金额 = 该订单已收且尚未退的（基础费/超时费）之和（已存在的退款单 WAIT/SUCCESS 均计入已退） */
    public int refundableAmount(Long orderId) {
        if (orderId == null) {
            return 0;
        }
        return accumulate(
                paymentRecordDao.selectList(new LambdaQueryWrapper<PaymentRecordEntity>()
                        .eq(PaymentRecordEntity::getOrderId, orderId)),
                refundRecordDao.selectList(new LambdaQueryWrapper<RefundRecordEntity>()
                        .eq(RefundRecordEntity::getOrderId, orderId)));
    }

    /**
     * 批量可退金额（S8 批次31：订单列表页用）。
     *
     * <p>为什么必须有这个批量口：列表一行一个 {@link #refundableAmount(Long)} 就是 2×N 次查询（N+1）。
     * 这里一次 {@code in} 取本页全部流水，再按订单内存聚合；<b>加减规则与单订单口径共用
     * {@link #accumulate}</b>——两个入口一个口径，杜绝"列表显示可退 3 元、详情显示 5 元"。
     * 返回的 map 对入参里每个非空 orderId 都有键（无可退流水则为 0），调用方不必再判空。
     */
    public Map<Long, Integer> refundableAmounts(java.util.Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return java.util.Map.of();
        }
        List<Long> ids = orderIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        Map<Long, List<PaymentRecordEntity>> payments = new java.util.LinkedHashMap<>();
        for (PaymentRecordEntity record : paymentRecordDao.selectList(
                new LambdaQueryWrapper<PaymentRecordEntity>().in(PaymentRecordEntity::getOrderId, ids))) {
            if (record.getOrderId() != null) {
                payments.computeIfAbsent(record.getOrderId(), key -> new java.util.ArrayList<>()).add(record);
            }
        }
        Map<Long, List<RefundRecordEntity>> refunds = new java.util.LinkedHashMap<>();
        for (RefundRecordEntity record : refundRecordDao.selectList(
                new LambdaQueryWrapper<RefundRecordEntity>().in(RefundRecordEntity::getOrderId, ids))) {
            if (record.getOrderId() != null) {
                refunds.computeIfAbsent(record.getOrderId(), key -> new java.util.ArrayList<>()).add(record);
            }
        }
        Map<Long, Integer> result = new java.util.LinkedHashMap<>();
        for (Long id : ids) {
            result.put(id, accumulate(payments.getOrDefault(id, List.of()), refunds.getOrDefault(id, List.of())));
        }
        return result;
    }

    /** 可退口径的唯一实现：只认收费类型白名单 + 扣减已退，并夹到 0（负值不外泄）。 */
    private int accumulate(List<PaymentRecordEntity> payments, List<RefundRecordEntity> refunds) {
        int sum = 0;
        for (PaymentRecordEntity record : payments) {
            if (REFUNDABLE_TYPES.contains(record.getPaymentType())
                    && record.getAmountFen() != null && record.getAmountFen() > 0) {
                sum += record.getAmountFen();
            }
        }
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
