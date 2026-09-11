package com.swapops.server.common.lock;

import com.swapops.server.device.config.SwapRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 定时任务租约锁（S3.3）：多实例部署下同一扫描任务只在一个实例执行。
 *
 * <p>语义为"短租约"：tryLock 以 SET NX PX 获取；任务须在 TTL 内完成（超时自动释放，
 * 由下一轮补扫兜底——本项目所有扫描任务本身幂等）。释放用 LUA 比对持有者，避免误删他人的锁。
 * 异常向上抛（调用方决定本轮是否跳过），锁获取失败不视为错误（跳过本轮即正常多实例行为）。</p>
 */
@Slf4j
@Component
public class JobLockService {

    /**
     * LUA：仅当 value 匹配持有者 token 才删除（防误删）
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final Duration lockTtl;

    public JobLockService(StringRedisTemplate stringRedisTemplate,
                          @Value("${swap.job.lock-ttl-seconds:60}") long lockTtlSeconds) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
    }

    /**
     * 带租约执行：抢到锁才执行；未抢到返回 false（另一实例在执行）。
     * 任务异常照常抛出（调用方/Scheduled 层记录），锁在 finally 释放。
     */
    public boolean runWithLock(String jobName, Runnable task) {
        String key = SwapRedisKeys.JOB_LOCK_PREFIX + jobName;
        String token = UUID.randomUUID().toString().replace("-", "");
        Boolean acquired;
        try {
            acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, token, lockTtl);
        } catch (RuntimeException e) {
            // Redis 故障：宁可双跑不可停扫（扫描任务幂等；安全侧允许降级）
            log.warn("任务锁获取异常，降级直接执行 job={} cause={}", jobName, e.getMessage());
            task.run();
            return true;
        }
        if (!Boolean.TRUE.equals(acquired)) {
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            try {
                stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
            } catch (RuntimeException e) {
                log.warn("任务锁释放异常（TTL 到期自动释放）job={} cause={}", jobName, e.getMessage());
            }
        }
    }
}
