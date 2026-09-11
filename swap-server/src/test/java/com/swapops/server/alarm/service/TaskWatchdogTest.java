package com.swapops.server.alarm.service;

import com.swapops.server.alarm.AlarmType;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.device.config.SwapRedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务看护单测（S3.6）：停摆告警/恢复自动关/首轮基线宽限。
 */
@DisplayName("定时任务看护")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskWatchdogTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private AlarmService alarmService;
    @Mock
    private JobLockService jobLockService;

    private TaskWatchdog watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new TaskWatchdog(redis, alarmService, jobLockService);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return true;
        });
    }

    @Test
    @DisplayName("心跳超阈值：逐任务告警 JOB_STALLED")
    void 停摆告警() {
        when(valueOps.get(anyString())).thenReturn(String.valueOf(System.currentTimeMillis() - 999_999_999L));

        watchdog.scan();

        int jobs = TaskWatchdog.registeredJobs().size();
        verify(alarmService, times(jobs)).raise(eq(AlarmService.DEVICE_JOB), anyString(),
                eq(AlarmType.JOB_STALLED), anyString());
    }

    @Test
    @DisplayName("心跳新鲜：自动关闭告警")
    void 恢复自动关() {
        when(valueOps.get(anyString())).thenReturn(String.valueOf(System.currentTimeMillis()));

        watchdog.scan();

        int jobs = TaskWatchdog.registeredJobs().size();
        verify(alarmService, times(jobs)).markRecovered(eq(AlarmService.DEVICE_JOB), anyString(),
                eq(AlarmType.JOB_STALLED));
        verify(alarmService, never()).raise(anyString(), anyString(), eq(AlarmType.JOB_STALLED), anyString());
    }

    @Test
    @DisplayName("首轮无记录：写基线宽限，不告警")
    void 首轮基线宽限() {
        when(valueOps.get(anyString())).thenReturn(null);

        watchdog.scan();

        verify(valueOps, times(TaskWatchdog.registeredJobs().size()))
                .set(anyString(), anyString());
        verify(alarmService, never()).raise(anyString(), anyString(), eq(AlarmType.JOB_STALLED), anyString());
    }

    @Test
    @DisplayName("beat：写最近成功时刻")
    void beat写入() {
        watchdog.beat("order-sweep");

        verify(valueOps).set(eq(SwapRedisKeys.JOB_LAST_PREFIX + "order-sweep"), anyString());
    }
}
