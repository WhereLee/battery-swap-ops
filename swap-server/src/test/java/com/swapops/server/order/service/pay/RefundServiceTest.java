package com.swapops.server.order.service.pay;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.common.delay.DelayQueueService;
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
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    private RefundService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, RefundRecordEntity.class);
        TableInfoHelper.initTableInfo(assistant, PaymentRecordEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new RefundService(refundRecordDao, paymentRecordDao,
                paymentRecordService, walletService, delayQueueService);
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
    @DisplayName("可退金额：仅基础费/押金/超时费，排除已退/0 元/其他")
    void 可退金额口径() {
        when(paymentRecordDao.selectList(any())).thenReturn(List.of(
                payment(7L, 99L, PaymentType.BALANCE_FEE, 300),
                payment(7L, 99L, PaymentType.DEPOSIT, 9900),
                payment(7L, 99L, PaymentType.OVERDUE_FEE, 200),
                payment(7L, 99L, PaymentType.REFUND, 9900),
                payment(7L, 99L, PaymentType.PLAN_DEDUCT, 0)));

        assertThat(service.refundableAmount(99L)).isEqualTo(10400);
    }

    private PaymentRecordEntity payment(Long userId, Long orderId, PaymentType type, int amountFen) {
        PaymentRecordEntity record = new PaymentRecordEntity();
        record.setUserId(userId);
        record.setOrderId(orderId);
        record.setPaymentType(type.name());
        record.setAmountFen(amountFen);
        return record;
    }
}
