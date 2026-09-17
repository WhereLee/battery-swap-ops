package com.swapops.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 平台告警只读视图（GET /admin/alarm 元素；字段与平台 AlarmEntity 的 view 对齐）。
 * handled：0 未处理 / 1 已处理（自动恢复或人工）。
 *
 * <p>ignoreUnknown：平台 view 可能多返回 handler/handledTime 等 Agent 用不到的字段，
 * 且将来可能继续加字段——外部契约演进不得破坏消费者，故显式容忍未知字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlarmView(long id, String deviceType, String deviceNo, String alarmType,
                        String content, Integer handled, Long createTime) {
}
