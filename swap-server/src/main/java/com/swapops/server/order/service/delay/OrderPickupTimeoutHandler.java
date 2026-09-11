package com.swapops.server.order.service.delay;

import com.swapops.contract.OrderStatus;
import com.swapops.server.common.delay.DelayTaskHandler;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.SwapOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 取电超时（S3.3）：OPENED 到点未取电/还电 → TIMEOUT_CLOSED + 释放预占。
 * 幂等：以 DB 现态为准。
 */
@Slf4j
@Component
public class OrderPickupTimeoutHandler implements DelayTaskHandler {

    private final SwapOrderService swapOrderService;

    public OrderPickupTimeoutHandler(SwapOrderService swapOrderService) {
        this.swapOrderService = swapOrderService;
    }

    @Override
    public String topic() {
        return OrderDelayTopics.PICKUP_TIMEOUT;
    }

    @Override
    public void handle(String payload) {
        String orderNo = OrderDelayPayload.orderNo(payload);
        if (orderNo == null) {
            log.warn("取电超时载荷非法，跳过 payload={}", payload);
            return;
        }
        SwapOrderEntity order = swapOrderService.findByOrderNo(orderNo);
        if (order == null) {
            return;
        }
        if (order.getStatus() == OrderStatus.OPENED.getCode()) {
            swapOrderService.closeTimedOut(order, "PICKUP_TIMEOUT");
        }
    }
}
