package com.swapops.server.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.server.alarm.service.TaskWatchdog;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.device.config.SwapRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 日终对账任务（S3.5）：每日 02:00 执行，报告存 Redis（管理端可查，最近一次）；
 * 多实例由租约锁互斥。差异告警 S3.6 接管（当前 error 日志）。
 */
@Slf4j
@Component
public class DailyReconcileTask {

    private final ReconcileService reconcileService;
    private final JobLockService jobLockService;
    private final StringRedisTemplate stringRedisTemplate;
    private final TaskWatchdog watchdog;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DailyReconcileTask(ReconcileService reconcileService, JobLockService jobLockService,
                              StringRedisTemplate stringRedisTemplate, TaskWatchdog watchdog) {
        this.reconcileService = reconcileService;
        this.jobLockService = jobLockService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.watchdog = watchdog;
    }

    @Scheduled(cron = "${swap.reconcile.cron:0 0 2 * * ?}")
    public void dailyRun() {
        if (!jobLockService.runWithLock("daily-reconcile", this::runAndStore)) {
            log.debug("日终对账：另一实例执行中，跳过本轮");
        }
    }

    /** 执行并落 Redis（管理端手动触发复用） */
    public Map<String, Object> runAndStore() {
        ReconcileService.ReconcileReport report = reconcileService.run();
        Map<String, Object> map = report.toMap();
        try {
            stringRedisTemplate.opsForValue().set(SwapRedisKeys.RECONCILE_LAST,
                    objectMapper.writeValueAsString(map), Duration.ofDays(30));
        } catch (Exception e) {
            log.warn("对账报告写 Redis 失败（不影响核查结论） cause={}", e.getMessage());
        }
        watchdog.beat("daily-reconcile");
        return map;
    }

    /** 最近一次报告（无则 null） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> lastReport() {
        String raw = stringRedisTemplate.opsForValue().get(SwapRedisKeys.RECONCILE_LAST);
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            log.warn("对账报告解析失败: {}", e.getMessage());
            return null;
        }
    }
}
