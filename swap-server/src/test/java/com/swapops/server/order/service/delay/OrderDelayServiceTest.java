package com.swapops.server.order.service.delay;

import com.swapops.server.common.delay.DelayQueueService;
import com.swapops.server.config.BillingProperties;
import com.swapops.server.order.entity.SwapOrderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单延迟任务登记单测（S3.3）：三段计时器的时刻换算与取消。
 */
@DisplayName("订单延迟任务登记")
@ExtendWith(MockitoExtension.class)
class OrderDelayServiceTest {

    @Mock
    private DelayQueueService delayQueueService;

    private BillingProperties billingProperties;
    private OrderDelayService service;

    @BeforeEach
    void setUp() {
        billingProperties = new BillingProperties();
        service = new OrderDelayService(delayQueueService, billingProperties);
    }

    private SwapOrderEntity order() {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setOrderNo("SWO-1");
        return order;
    }

    @Test
    @DisplayName("预占计时：score=preemptExpireTime")
    void 预占计时() {
        SwapOrderEntity order = order();
        order.setPreemptExpireTime(111_000L);

        service.schedulePreempt(order);

        verify(delayQueueService).enqueue(eq(OrderDelayTopics.PREEMPT_TIMEOUT), eq("SWO-1"),
                eq(OrderDelayPayload.of("SWO-1")), eq(111_000L));
    }

    @Test
    @DisplayName("取电计时：score=开仓时刻+pickupTimeoutSeconds")
    void 取电计时() {
        service.schedulePickup(order(), 50_000L);

        verify(delayQueueService).enqueue(eq(OrderDelayTopics.PICKUP_TIMEOUT), eq("SWO-1"),
                anyString(), eq(50_000L + billingProperties.getPickupTimeoutSeconds() * 1000L));
    }

    @Test
    @DisplayName("归还超期计时：score=取电时刻+overdueHours")
    void 超期计时() {
        service.scheduleOverdue(order(), 60_000L);

        verify(delayQueueService).enqueue(eq(OrderDelayTopics.OVERDUE), eq("SWO-1"),
                anyString(), eq(60_000L + billingProperties.getOverdueHours() * 3600_000L));
    }

    @Test
    @DisplayName("终态清理：三个主题全部取消")
    void 终态清理() {
        service.cancelAll(order());

        verify(delayQueueService).cancel(OrderDelayTopics.PREEMPT_TIMEOUT, "SWO-1");
        verify(delayQueueService).cancel(OrderDelayTopics.PICKUP_TIMEOUT, "SWO-1");
        verify(delayQueueService).cancel(OrderDelayTopics.OVERDUE, "SWO-1");
    }

    @Test
    @DisplayName("登记失败不阻断主流程（扫描兜底）")
    void 登记失败吞异常() {
        SwapOrderEntity order = order();
        order.setPreemptExpireTime(111_000L);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(delayQueueService).enqueue(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());

        service.schedulePreempt(order);
    }

    @Test
    @DisplayName("取消异常不阻断（终态判定保证空转无害）")
    void 取消失败吞异常() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(delayQueueService).cancel(anyString(), anyString());

        service.cancelAll(order());
    }
}
