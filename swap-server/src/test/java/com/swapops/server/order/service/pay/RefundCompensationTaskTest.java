package com.swapops.server.order.service.pay;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.contract.OrderStatus;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 异常订单退款补偿单测（S3.4）：EXCEPTION 订单自动核算退款；租约锁未抢到跳过。
 */
@DisplayName("退款补偿任务")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundCompensationTaskTest {

    @Mock
    private SwapOrderDao orderDao;
    @Mock
    private RefundService refundService;
    @Mock
    private JobLockService jobLockService;
    @Mock
    private com.swapops.server.alarm.service.TaskWatchdog watchdog;

    private RefundCompensationTask task;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SwapOrderEntity.class);
    }

    @BeforeEach
    void setUp() {
        task = new RefundCompensationTask(orderDao, refundService, jobLockService, watchdog);
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return true;
        });
    }

    @Test
    @DisplayName("EXCEPTION 订单：按可退金额自动退款（原因 ORDER_EXCEPTION）")
    void 异常订单自动退款() {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setUserId(7L);
        order.setStatus(OrderStatus.EXCEPTION.getCode());
        when(orderDao.selectList(any())).thenReturn(List.of(order));
        when(refundService.refundableAmount(99L)).thenReturn(300);
        when(refundService.refund(99L, 7L, 300, "ORDER_EXCEPTION"))
                .thenReturn(new com.swapops.server.order.entity.RefundRecordEntity());

        task.compensate();

        verify(refundService).refund(99L, 7L, 300, "ORDER_EXCEPTION");
    }

    @Test
    @DisplayName("租约锁未抢到：跳过本轮")
    void 未抢到锁跳过() {
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenReturn(false);

        task.compensate();

        verifyNoInteractions(orderDao, refundService);
    }
}
