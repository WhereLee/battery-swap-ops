package com.swapops.server.outbox.service;

import com.swapops.server.alarm.service.AlarmEventPublisher;
import com.swapops.server.outbox.entity.OutboxEventEntity;
import org.springframework.stereotype.Component;

/**
 * 告警事件发布器（S3.8 WP6 首批接入）：outbox（type=ALARM）→ topic swap-alarm。
 */
@Component
public class AlarmOutboxPublisher implements OutboxPublisher {

    public static final String EVENT_TYPE_ALARM = "ALARM";

    private final AlarmEventPublisher alarmEventPublisher;

    public AlarmOutboxPublisher(AlarmEventPublisher alarmEventPublisher) {
        this.alarmEventPublisher = alarmEventPublisher;
    }

    @Override
    public boolean supports(String eventType) {
        return EVENT_TYPE_ALARM.equals(eventType);
    }

    @Override
    public void publish(OutboxEventEntity event) {
        alarmEventPublisher.publishEnvelope(event.getPayload(), event.getTraceId(),
                "alarm-outbox-" + event.getEventKey());
    }
}
