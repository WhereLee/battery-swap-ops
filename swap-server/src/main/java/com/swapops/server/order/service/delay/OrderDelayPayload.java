package com.swapops.server.order.service.delay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 订单延迟任务载荷编解码（{"orderNo":"..."}）。
 * 载荷故意只带 orderNo：执行时刻以 DB 现态为准（避免携带过期快照）。
 */
public final class OrderDelayPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrderDelayPayload() {
    }

    public static String of(String orderNo) {
        return "{\"orderNo\":\"" + orderNo + "\"}";
    }

    /** 解析 orderNo；载荷非法返回 null（处理器侧记警告并跳过） */
    public static String orderNo(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            JsonNode value = node == null ? null : node.get("orderNo");
            return value == null || !value.isTextual() ? null : value.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
