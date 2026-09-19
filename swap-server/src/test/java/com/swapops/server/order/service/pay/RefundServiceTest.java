package com.swapops.server.order.service.pay;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.common.delay.DelayQueueService;
import com.swapops.server.common.id.SnowflakeIdGenerator;
import com.swapops.server.order.dao.PaymentRecordDao;
import com.swapops.server.order.dao.RefundRecordDao;
import com.swapops.server.order.entity.PaymentRecordEntity;
import com.swapops.server.order.entity.RefundRecordEntity;
import com.swapops.server.order.enums.PaymentType;
import com.swapops.server.order.enums.RefundStatus;
import com.swapops.server.order.service.PaymentRecordService;
import com.swapops.server.user.service.WalletService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 退款服务单测（S3.4）：幂等闸（order_id+reason 唯一）、CAS 标记、金额 0 仅核销、失败转延迟重试、可退金额口径。
 */
@DisplayName("退款服务（幂等 + 补偿）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundServiceTest {

    @Mock
    private RefundRecordDao refundRecordDao;
    @Mock
    private PaymentRecordDao paymentRecordDao;
    @Mock
    private PaymentRecordService paymentRecordService;
    @Mock
    private WalletService walletService;
    @Mock
    private DelayQueueService delayQueueService;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    @Mock
    private com.swapops.server.user.service.UserMessageService messageService;

    @Mock
    private com.swapops.server.settlement.service.SettlementService settlementService;
    @Mock
    private com.swapops.server.order.dao.SwapOrderDao swapOrderDao;

    /**
     * 事务管理器用桩：资金动作的事务边界是批次32 的修复重点，这里断言的是
     * "apply 确实开了事务、成功提交、失败回滚"——事务语义本身由 Spring 保证。
     */
    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private RefundService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, RefundRecordEntity.class);
        TableInfoHelper.initTableInfo(assistant, PaymentRecordEntity.class);
    }

    @BeforeEach
    void setUp() {
        when(idGenerator.nextIdString()).thenReturn("123456");
        when(transactionManager.getTransaction(any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        service = new RefundService(refundRecordDao, paymentRecordDao,
                paymentRecordService, walletService, delayQueueService, idGenerator,
                new com.swapops.server.common.retry.DeadlockRetryExecutor(), messageService,
                settlementService, swapOrderDao, transactionManager);
        // 默认可退口径：该订单已收 300（基础费），无历史退款（S5 审查：refund 入口先做可退上限校验）
        when(paymentRecordDao.selectList(any())).thenReturn(List.of(
                payment(7L, 99L, PaymentType.BALANCE_FEE, 300)));
        when(refundRecordDao.selectList(any())).thenReturn(List.of());
    }

    private RefundRecordEntity record(String status, int amountFen) {
        RefundRecordEntity record = new RefundRecordEntity();
        record.setId(5L);
        record.setRefundNo("RF1");
        record.setOrderId(99L);
        record.setUserId(7L);
        record.setAmountFen(amountFen);
        record.setReason("ADMIN_MANUAL");
        record.setStatus(status);
        return record;
    }

    @Test
    @DisplayName("首次退款：入余额 + REFUND 流水 + CAS 标记 SUCCESS")
    void 首次退款成功() {
        when(refundRecordDao.selectOne(any())).thenReturn(null);
        when(refundRecordDao.insert(any(RefundRecordEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundRecordEntity.class).setId(5L);
            return 1;
        });
        when(refundRecordDao.update(isNull(), any())).thenReturn(1);
        when(refundRecordDao.selectById(anyLong())).thenReturn(record(RefundStatus.SUCCESS.name(), 300));

        RefundRecordEntity result = service.refund(99L, 7L, 300, "ADMIN_MANUAL");

        assertThat(result.getStatus()).isEqualTo(RefundStatus.SUCCESS.name());
        verify(walletService).addBalance(7L, 300);
        verify(paymentRecordService).record(7L, 99L, PaymentType.REFUND, 300, "退款:ADMIN_MANUAL");
        verify(delayQueueService, never()).enqueue(anyString(), anyString(), anyString(), anyLong());
        // S7 WP-B：退款成功同事务写冲正流水（订单未分账时由服务内跳过）
        verify(settlementService).recordRefundReversal(isNull(), any(RefundRecordEntity.class));
    }

    @Test
    @DisplayName("重复发起：既有记录直接返回，不二次入账")
    void 重复退款幂等() {
        when(refundRecordDao.selectOne(any())).thenReturn(record(RefundStatus.SUCCESS.name(), 300));

        RefundRecordEntity result = service.refund(99L, 7L, 300, "ADMIN_MANUAL");

        assertThat(result.getRefundNo()).isEqualTo("RF1");
        verify(refundRecordDao, never()).insert(any(RefundRecordEntity.class));
        verifyNoInteractions(walletService, paymentRecordService, delayQueueService);
    }

    @Test
    @DisplayName("并发插入冲突：唯一键兜底，返回既有记录")
    void 并发插入冲突() {
        when(refundRecordDao.selectOne(any())).thenReturn(null, record(RefundStatus.SUCCESS.name(), 300));
        when(refundRecordDao.insert(any(RefundRecordEntity.class))).thenThrow(new DuplicateKeyException("uk"));

        RefundRecordEntity result = service.refund(99L, 7L, 300, "ADMIN_MANUAL");

        assertThat(result.getRefundNo()).isEqualTo("RF1");
        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("金额 0：仅落单核销，无资金动作")
    void 零金额仅核销() {
        when(refundRecordDao.selectOne(any())).thenReturn(null);
        when(refundRecordDao.insert(any(RefundRecordEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundRecordEntity.class).setId(5L);
            return 1;
        });
        when(refundRecordDao.update(isNull(), any())).thenReturn(1);
        when(refundRecordDao.selectById(anyLong())).thenReturn(record(RefundStatus.SUCCESS.name(), 0));

        service.refund(99L, 7L, 0, "ORDER_EXCEPTION");

        verifyNoInteractions(walletService, paymentRecordService);
    }

    @Test
    @DisplayName("执行失败：留 WAIT 并登记延迟重试（不抛出）")
    void 执行失败转延迟重试() {
        when(refundRecordDao.selectOne(any())).thenReturn(null);
        when(refundRecordDao.insert(any(RefundRecordEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundRecordEntity.class).setId(5L);
            return 1;
        });
        when(refundRecordDao.selectById(anyLong())).thenReturn(record(RefundStatus.WAIT.name(), 300));
        doThrow(new RuntimeException("db down")).when(walletService).addBalance(7L, 300);

        RefundRecordEntity result = service.refund(99L, 7L, 300, "ADMIN_MANUAL");

        assertThat(result.getStatus()).isEqualTo(RefundStatus.WAIT.name());
        verify(delayQueueService).enqueue(anyString(), anyString(), anyString(), anyLong());
        verify(paymentRecordService, never()).record(any(), any(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("可退金额：仅基础费/超时费；押金（走退租流程）/已退/0 元/其他排除（S7 WP-0 修正）")
    void 可退金额口径() {
        when(paymentRecordDao.selectList(any())).thenReturn(List.of(
                payment(7L, 99L, PaymentType.BALANCE_FEE, 300),
                payment(7L, 99L, PaymentType.DEPOSIT, 9900),
                payment(7L, 99L, PaymentType.OVERDUE_FEE, 200),
                payment(7L, 99L, PaymentType.REFUND, 9900),
                payment(7L, 99L, PaymentType.PLAN_DEDUCT, 0)));

        assertThat(service.refundableAmount(99L)).isEqualTo(500);
    }

    @Test
    @DisplayName("押金二次退款防护：仅有押金流水的订单可退金额为 0（S7 WP-0）")
    void 押金不计入可退() {
        when(paymentRecordDao.selectList(any())).thenReturn(List.of(
                payment(7L, 99L, PaymentType.DEPOSIT, 9900)));

        assertThat(service.refundableAmount(99L)).isZero();
    }

    @Test
    @DisplayName("已退款扣减：可退金额 = 已收 - 已退（S5 审查修复：防自动+人工双通道双退）")
    void 可退金额扣减已退() {
        when(paymentRecordDao.selectList(any())).thenReturn(List.of(
                payment(7L, 99L, PaymentType.BALANCE_FEE, 300),
                payment(7L, 99L, PaymentType.OVERDUE_FEE, 200)));
        when(refundRecordDao.selectList(any())).thenReturn(List.of(
                record(RefundStatus.SUCCESS.name(), 200)));

        assertThat(service.refundableAmount(99L)).isEqualTo(300);
    }

    @Test
    @DisplayName("超额退款拒绝：金额超可退上限直接抛错（S5 审查修复）")
    void 超额退款拒绝() {
        assertThatThrownBy(() -> service.refund(99L, 7L, 500, "ADMIN_MANUAL"))
                .isInstanceOf(com.swapops.server.common.RRException.class)
                .hasMessageContaining("超过可退金额");
        verify(refundRecordDao, never()).insert(any(RefundRecordEntity.class));
        verifyNoInteractions(walletService);
    }

    private PaymentRecordEntity payment(Long userId, Long orderId, PaymentType type, int amountFen) {
        PaymentRecordEntity record = new PaymentRecordEntity();
        record.setUserId(userId);
        record.setOrderId(orderId);
        record.setPaymentType(type.name());
        record.setAmountFen(amountFen);
        return record;
    }

    // ---------- 批次32：资金动作的事务边界（P0 缺陷修复的回归网） ----------

    @Test
    @DisplayName("事务边界：退款成功走提交（不是自动提交的各写各的）")
    void 退款成功提交事务() {
        when(refundRecordDao.selectOne(any())).thenReturn(null);
        when(refundRecordDao.insert(any(RefundRecordEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundRecordEntity.class).setId(5L);
            return 1;
        });
        when(refundRecordDao.update(isNull(), any())).thenReturn(1);
        when(refundRecordDao.selectById(anyLong())).thenReturn(record(RefundStatus.SUCCESS.name(), 300));

        service.refund(99L, 7L, 300, "ADMIN_MANUAL");

        verify(transactionManager).getTransaction(any());
        verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    @DisplayName("事务边界：CAS 冲突 → 回滚（加款随之撤销），单子留 WAIT 等重驱动，不产生二次入账")
    void CAS冲突回滚资金动作() {
        when(refundRecordDao.selectOne(any())).thenReturn(null);
        when(refundRecordDao.insert(any(RefundRecordEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundRecordEntity.class).setId(5L);
            return 1;
        });
        when(refundRecordDao.update(isNull(), any())).thenReturn(0); // 被并发抢先标记 SUCCESS
        when(refundRecordDao.selectById(anyLong())).thenReturn(record(RefundStatus.WAIT.name(), 300));

        RefundRecordEntity result = service.refund(99L, 7L, 300, "ADMIN_MANUAL");

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        assertThat(result.getStatus()).as("回滚后单子仍在 WAIT").isEqualTo(RefundStatus.WAIT.name());
        verify(delayQueueService).enqueue(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("WAIT 重驱动：再次发起会重跑资金动作（旧实现命中 WAIT 直接返回 → 钱永久悬空）")
    void WAIT单可重驱动() {
        when(refundRecordDao.selectOne(any())).thenReturn(record(RefundStatus.WAIT.name(), 300));
        when(refundRecordDao.update(isNull(), any())).thenReturn(1);
        when(refundRecordDao.selectById(anyLong())).thenReturn(record(RefundStatus.SUCCESS.name(), 300));

        RefundRecordEntity result = service.refund(99L, 7L, 300, "ADMIN_MANUAL");

        verify(walletService).addBalance(7L, 300);
        verify(transactionManager).commit(any());
        assertThat(result.getStatus()).isEqualTo(RefundStatus.SUCCESS.name());
    }

    @Test
    @DisplayName("SUCCESS 终态不再重驱动：重复发起幂等返回既有单，钱包不再变动")
    void 成功单不重复入账() {
        when(refundRecordDao.selectOne(any())).thenReturn(record(RefundStatus.SUCCESS.name(), 300));

        RefundRecordEntity result = service.refund(99L, 7L, 300, "ADMIN_MANUAL");

        assertThat(result.getStatus()).isEqualTo(RefundStatus.SUCCESS.name());
        verifyNoInteractions(walletService);
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    @DisplayName("批量可退（S8 批次31 列表用）：按订单分组、口径与单订单一致、入参每个 id 都有键")
    void 批量可退金额() {
        when(paymentRecordDao.selectList(any())).thenReturn(List.of(
                payment(7L, 99L, PaymentType.BALANCE_FEE, 300),
                payment(7L, 99L, PaymentType.DEPOSIT, 9900),
                payment(7L, 100L, PaymentType.OVERDUE_FEE, 200),
                payment(7L, 101L, PaymentType.DEPOSIT, 9900)));
        when(refundRecordDao.selectList(any())).thenReturn(List.of(refund(99L, 100)));

        java.util.Map<Long, Integer> amounts = service.refundableAmounts(List.of(99L, 100L, 101L));

        assertThat(amounts).containsEntry(99L, 200);  // 300 - 100（押金不计入）
        assertThat(amounts).containsEntry(100L, 200); // 仅超时费
        assertThat(amounts).containsEntry(101L, 0);   // 仅押金 → 0，仍然有键
    }

    @Test
    @DisplayName("批量可退：空入参/全 null 不查库，直接返回空 map（列表页无行时不产生 SQL）")
    void 批量可退空入参() {
        assertThat(service.refundableAmounts(List.of())).isEmpty();
        assertThat(service.refundableAmounts(java.util.Arrays.asList(null, null))).isEmpty();
        assertThat(service.refundableAmounts(null)).isEmpty();
        verifyNoInteractions(paymentRecordDao, refundRecordDao);
    }

    private RefundRecordEntity refund(Long orderId, int amountFen) {
        RefundRecordEntity record = new RefundRecordEntity();
        record.setOrderId(orderId);
        record.setAmountFen(amountFen);
        record.setStatus(RefundStatus.SUCCESS.name());
        return record;
    }
}
