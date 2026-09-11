package com.swapops.server.order.service.delay;

import com.swapops.contract.OrderStatus;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.SwapOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单三段计时器处理器单测（S3.3）：状态相符才动作（幂等），非法载荷/订单不存在安全跳过。
 */
@DisplayName("订单超时处理器（预占/取电/超期）")
@ExtendWith(MockitoExtension.class)
class OrderTimeoutHandlersTest {

    @Mock
    private SwapOrderService swapOrderService;

    private SwapOrderEntity order(OrderStatus status) {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setOrderNo("SWO-1");
        order.setStatus(status.getCode());
        return order;
    }

    @Test
    @DisplayName("预占超时：PENDING_OPEN 到点 → 关闭")
    void 预占超时关闭() {
        SwapOrderEntity order = order(OrderStatus.PENDING_OPEN);
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(order);
        new OrderPreemptTimeoutHandler(swapOrderService).handle(OrderDelayPayload.of("SWO-1"));

        verify(swapOrderService).closeTimedOut(order, "PREEMPT_TIMEOUT");
    }

    @Test
    @DisplayName("预占超时：状态已推进 → 空转（幂等）")
    void 预占超时已推进空转() {
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(order(OrderStatus.TAKEN));
        new OrderPreemptTimeoutHandler(swapOrderService).handle(OrderDelayPayload.of("SWO-1"));

        verify(swapOrderService, never()).closeTimedOut(any(), anyString());
    }

    @Test
    @DisplayName("取电超时：OPENED 到点 → 关闭")
    void 取电超时关闭() {
        SwapOrderEntity order = order(OrderStatus.OPENED);
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(order);
        new OrderPickupTimeoutHandler(swapOrderService).handle(OrderDelayPayload.of("SWO-1"));

        verify(swapOrderService).closeTimedOut(order, "PICKUP_TIMEOUT");
    }

    @Test
    @DisplayName("取电超时：已取电（COMPLETED）→ 空转")
    void 取电超时已完成空转() {
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(order(OrderStatus.COMPLETED));
        new OrderPickupTimeoutHandler(swapOrderService).handle(OrderDelayPayload.of("SWO-1"));

        verify(swapOrderService, never()).closeTimedOut(any(), anyString());
    }

    @Test
    @DisplayName("归还超期：TAKEN 到点 → 转 OVERDUE")
    void 归还超期转OVERDUE() {
        SwapOrderEntity order = order(OrderStatus.TAKEN);
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(order);
        when(swapOrderService.markOverdue(order)).thenReturn(true);
        new OrderOverdueHandler(swapOrderService).handle(OrderDelayPayload.of("SWO-1"));

        verify(swapOrderService).markOverdue(order);
    }

    @Test
    @DisplayName("归还超期：已归还（COMPLETED）→ 空转")
    void 归还超期已还空转() {
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(order(OrderStatus.COMPLETED));
        new OrderOverdueHandler(swapOrderService).handle(OrderDelayPayload.of("SWO-1"));

        verify(swapOrderService, never()).markOverdue(any());
    }

    @Test
    @DisplayName("非法载荷/订单不存在：安全跳过")
    void 非法载荷与订单不存在跳过() {
        new OrderPreemptTimeoutHandler(swapOrderService).handle("not-json");
        when(swapOrderService.findByOrderNo(eq("SWO-1"))).thenReturn(null);
        new OrderOverdueHandler(swapOrderService).handle(OrderDelayPayload.of("SWO-1"));

        verify(swapOrderService, never()).markOverdue(any());
        verify(swapOrderService, never()).closeTimedOut(any(), anyString());
    }
}
