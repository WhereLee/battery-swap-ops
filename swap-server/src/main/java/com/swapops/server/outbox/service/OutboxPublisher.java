package com.swapops.server.outbox.service;

import com.swapops.server.outbox.entity.OutboxEventEntity;

/**
 * outbox 事件发布器（S3.8 WP6）：按 eventType 路由；实现方失败需抛出（中继据此重试）。
 */
public interface OutboxPublisher {

    boolean supports(String eventType);

    void publish(OutboxEventEntity event);
}
