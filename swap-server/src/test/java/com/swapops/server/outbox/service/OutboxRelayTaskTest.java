package com.swapops.server.outbox.service;

import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.alarm.service.TaskWatchdog;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.outbox.config.OutboxProperties;
import com.swapops.server.outbox.entity.OutboxEventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * outbox 中继单测（S3.8 WP6）：成功 SENT、失败退避、超限死信+告警、无发布器死信、租约互斥。
 */
@DisplayName("outbox 中继")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayTaskTest {

    @Mock
    private OutboxService outboxService;
    @Mock
    private OutboxPublisher publisher;
    @Mock
    private JobLockService jobLockService;
    @Mock
    private AlarmService alarmService;
    @Mock
    private TaskWatchdog watchdog;

    private OutboxRelayTask task;

    @BeforeEach
    void setUp() {
        OutboxProperties properties = new OutboxProperties();
        properties.setMaxAttempts(5); // 测试固定上限（生产默认 20）
        task = new OutboxRelayTask(outboxService, List.of(publisher), properties,
                jobLockService, alarmService, watchdog);
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return true;
        });
        when(publisher.supports("ALARM")).thenReturn(true);
    }

    private OutboxEventEntity event(int attempts) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(1L);
        event.setEventKey("alarm:1:RAISED");
        event.setEventType("ALARM");
        event.setPayload("{}");
        event.setAttempts(attempts);
        return event;
    }

    @Test
    @DisplayName("投递成功：标记 SENT")
    void 投递成功() {
        OutboxEventEntity event = event(0);
        when(outboxService.findDue(anyLong(), any(Integer.class))).thenReturn(List.of(event));

        task.relay();

        verify(publisher).publish(event);
        verify(outboxService).markSent(1L);
        verify(watchdog).beat("outbox-relay");
    }

    @Test
    @DisplayName("投递失败：attempts+1 退避重排（未 SENT）")
    void 投递失败退避() {
        OutboxEventEntity event = event(0);
        when(outboxService.findDue(anyLong(), any(Integer.class))).thenReturn(List.of(event));
        doThrow(new RuntimeException("mq down")).when(publisher).publish(event);

        task.relay();

        verify(outboxService).markRetry(eq(1L), eq(1), anyLong(), contains("mq down"));
        verify(outboxService, never()).markSent(anyLong());
    }

    @Test
    @DisplayName("重试超限：DEAD + OUTBOX_DEAD 告警")
    void 超限死信告警() {
        OutboxEventEntity event = event(4); // maxAttempts=5 → 本次后达 5
        when(outboxService.findDue(anyLong(), any(Integer.class))).thenReturn(List.of(event));
        doThrow(new RuntimeException("mq down")).when(publisher).publish(event);

        task.relay();

        verify(outboxService).markDead(eq(1L), contains("mq down"));
        verify(alarmService).raise(eq(AlarmService.DEVICE_SYSTEM), eq("outbox"),
                eq(AlarmType.OUTBOX_DEAD), anyString());
    }

    @Test
    @DisplayName("无发布器：DEAD + 告警（配置错位可见）")
    void 无发布器死信() {
        OutboxEventEntity event = event(0);
        event.setEventType("UNKNOWN");
        when(outboxService.findDue(anyLong(), any(Integer.class))).thenReturn(List.of(event));

        task.relay();

        verify(outboxService).markDead(eq(1L), contains("无发布器"));
        verify(alarmService).raise(eq(AlarmService.DEVICE_SYSTEM), eq("outbox"),
                eq(AlarmType.OUTBOX_DEAD), anyString());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("租约锁未抢到：跳过本轮")
    void 未抢锁跳过() {
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenReturn(false);

        task.relay();

        verifyNoInteractions(outboxService, publisher, alarmService);
    }
}
