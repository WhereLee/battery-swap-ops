package com.swapops.server.order.service.delay;

import com.swapops.contract.OrderStatus;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.common.delay.DelayTaskHandler;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.SwapOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 归还超期（S3.3）：SWAP 取电后超过 overdueHours 未归还 → OVERDUE（进入超时计费）。
 * 幂等：以 DB 现态为准；已归还（COMPLETED）则空转。转 OVERDUE 同时产生 ORDER_OVERDUE 告警（S3.6）。
 */
@Slf4j
@Component
public class OrderOverdueHandler implements DelayTaskHandler {

    private final SwapOrderService swapOrderService;
    private final AlarmService alarmService;

    public OrderOverdueHandler(SwapOrderService swapOrderService, AlarmService alarmService) {
        this.swapOrderService = swapOrderService;
        this.alarmService = alarmService;
    }

    @Override
    public String topic() {
        return OrderDelayTopics.OVERDUE;
    }

    @Override
    public void handle(String payload) {
        String orderNo = OrderDelayPayload.orderNo(payload);
        if (orderNo == null) {
            log.warn("归还超期载荷非法，跳过 payload={}", payload);
            return;
        }
        SwapOrderEntity order = swapOrderService.findByOrderNo(orderNo);
        if (order == null) {
            return;
        }
        if (order.getStatus() == OrderStatus.TAKEN.getCode()) {
            if (swapOrderService.markOverdue(order)) {
                alarmService.raise(AlarmService.DEVICE_ORDER, order.getOrderNo(), AlarmType.ORDER_OVERDUE,
                        "归还超期 userId=" + order.getUserId() + " takeTime=" + order.getTakeTime());
                log.error("订单归还超期转 OVERDUE orderNo={} userId={} takeTime={}",
                        order.getOrderNo(), order.getUserId(), order.getTakeTime());
            }
        }
    }
}
