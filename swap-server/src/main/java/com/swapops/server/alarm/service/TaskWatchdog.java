package com.swapops.server.alarm.service;

import com.swapops.server.alarm.AlarmType;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.device.config.SwapRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定时任务看护（S3.6）：每个任务成功执行时 beat(name) 写最近成功时刻；
 * 超过登记周期的 N 倍未见心跳 → JOB_STALLED；恢复自动关。
 * 首次未见时刻=注册基线（给一个周期宽限，避免启动瞬间误报）。
 */
@Slf4j
@Component
public class TaskWatchdog {

    /** 登记任务与"视为停摆"的阈值毫秒（周期 × 余量） */
    private static final Map<String, Long> JOBS = Map.of(
            "order-sweep", 60_000L,
            "monitor-reconcile", 180_000L,
            "refund-compensation", 300_000L,
            "delay-scheduler", 60_000L,
            "offline-scan", 300_000L,
            "outbox-relay", 300_000L,
            "battery-health-scan", 2 * 3600_000L,
            "daily-reconcile", 26 * 3600_000L);

    private final StringRedisTemplate stringRedisTemplate;
    private final AlarmService alarmService;
    private final JobLockService jobLockService;

    public TaskWatchdog(StringRedisTemplate stringRedisTemplate, AlarmService alarmService,
                        JobLockService jobLockService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.alarmService = alarmService;
        this.jobLockService = jobLockService;
    }

    /** 任务成功心跳（各定时任务末尾调用） */
    public void beat(String jobName) {
        try {
            stringRedisTemplate.opsForValue().set(SwapRedisKeys.JOB_LAST_PREFIX + jobName,
                    String.valueOf(System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("任务心跳写入失败 job={} cause={}", jobName, e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${swap.alarm.watchdog-interval-ms:60000}")
    public void scan() {
        if (!jobLockService.runWithLock("task-watchdog", this::doScan)) {
            log.debug("任务看护：另一实例执行中，跳过本轮");
        }
    }

    private void doScan() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : JOBS.entrySet()) {
            String job = entry.getKey();
            long staleMillis = entry.getValue();
            String raw = stringRedisTemplate.opsForValue().get(SwapRedisKeys.JOB_LAST_PREFIX + job);
            if (raw == null) {
                // 首次注册基线：无记录不判停摆（可能尚未运行过），写下基线供下轮判定
                beat(job);
                continue;
            }
            long last = parseLong(raw);
            if (last <= 0) {
                beat(job);
                continue;
            }
            if (now - last > staleMillis) {
                alarmService.raise(AlarmService.DEVICE_JOB, job, AlarmType.JOB_STALLED,
                        "任务停摆 " + (now - last) / 1000 + "s（阈值 " + staleMillis / 1000 + "s）");
            } else {
                alarmService.markRecovered(AlarmService.DEVICE_JOB, job, AlarmType.JOB_STALLED);
            }
        }
    }

    private long parseLong(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 供文档/测试：登记任务清单 */
    public static Map<String, Long> registeredJobs() {
        return new LinkedHashMap<>(JOBS);
    }
}
