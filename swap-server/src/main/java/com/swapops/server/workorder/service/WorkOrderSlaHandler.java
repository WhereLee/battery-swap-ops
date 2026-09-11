package com.swapops.server.workorder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.server.common.delay.DelayTaskHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工单 SLA 到点处理器（S4.4）：置超时位 + WORK_ORDER_SLA_BREACH 告警（幂等）。
 */
@Slf4j
@Component
public class WorkOrderSlaHandler implements DelayTaskHandler {

    private final WorkOrderService workOrderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkOrderSlaHandler(WorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    @Override
    public String topic() {
        return WorkOrderService.SLA_TOPIC;
    }

    @Override
    public void handle(String payload) {
        String woNo = parseWoNo(payload);
        if (woNo == null) {
            log.warn("工单 SLA 载荷非法，跳过 payload={}", payload);
            return;
        }
        workOrderService.markSlaBreached(woNo);
    }

    private String parseWoNo(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload).get("woNo");
            return node == null || !node.isTextual() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
