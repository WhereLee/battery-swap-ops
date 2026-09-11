package com.swapops.server.outbox.service;

import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.alarm.service.TaskWatchdog;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.outbox.config.OutboxProperties;
import com.swapops.server.outbox.entity.OutboxEventEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * outbox 中继（S3.8 WP6）：轮询 NEW 事件 → 按类型路由发布 → SENT/退避重试/DEAD。
 * 租约锁多实例互斥；发布失败退避（base×N），超限 DEAD + OUTBOX_DEAD 告警。
 */
@Slf4j
@Component
public class OutboxRelayTask {

    private final OutboxService outboxService;
    private final List<OutboxPublisher> publishers;
    private final OutboxProperties properties;
    private final JobLockService jobLockService;
    private final AlarmService alarmService;
    private final TaskWatchdog watchdog;

    public OutboxRelayTask(OutboxService outboxService, List<OutboxPublisher> publishers,
                           OutboxProperties properties, JobLockService jobLockService,
                           AlarmService alarmService, TaskWatchdog watchdog) {
        this.outboxService = outboxService;
        this.publishers = publishers;
        this.properties = properties;
        this.jobLockService = jobLockService;
        this.alarmService = alarmService;
        this.watchdog = watchdog;
    }

    @Scheduled(fixedDelayString = "${swap.outbox.relay-interval-ms:5000}")
    public void relay() {
        if (!jobLockService.runWithLock("outbox-relay", this::doRelay)) {
            log.debug("outbox 中继：另一实例执行中，跳过本轮");
        }
    }

    private void doRelay() {
        long now = System.currentTimeMillis();
        List<OutboxEventEntity> due = outboxService.findDue(now, properties.getBatch());
        for (OutboxEventEntity event : due) {
            OutboxPublisher publisher = publishers.stream()
                    .filter(p -> p.supports(event.getEventType()))
                    .findFirst()
                    .orElse(null);
            if (publisher == null) {
                outboxService.markDead(event.getId(), "无发布器: " + event.getEventType());
                alarmService.raise(AlarmService.DEVICE_SYSTEM, "outbox", AlarmType.OUTBOX_DEAD,
                        "outbox 无发布器 eventKey=" + event.getEventKey() + " type=" + event.getEventType());
                continue;
            }
            try {
                publisher.publish(event);
                outboxService.markSent(event.getId());
            } catch (Exception e) {
                retryOrDead(event, e.getMessage());
            }
        }
        watchdog.beat("outbox-relay");
    }

    private void retryOrDead(OutboxEventEntity event, String error) {
        int attempts = (event.getAttempts() == null ? 0 : event.getAttempts()) + 1;
        if (attempts >= properties.getMaxAttempts()) {
            outboxService.markDead(event.getId(), error);
            alarmService.raise(AlarmService.DEVICE_SYSTEM, "outbox", AlarmType.OUTBOX_DEAD,
                    "outbox 重试超限 eventKey=" + event.getEventKey()
                            + " attempts=" + attempts + " error=" + error);
            log.error("[outbox] 投递超限转死信 key={} attempts={} error={}",
                    event.getEventKey(), attempts, error);
            return;
        }
        long nextRetry = System.currentTimeMillis() + properties.getBackoffBaseMillis() * attempts;
        outboxService.markRetry(event.getId(), attempts, nextRetry, error);
        log.warn("[outbox] 投递失败将退避重试 key={} attempts={}/{} error={}",
                event.getEventKey(), attempts, properties.getMaxAttempts(), error);
    }
}
