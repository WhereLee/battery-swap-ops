package com.swapops.server.common.id;

import com.swapops.server.device.config.SwapRedisKeys;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Snowflake workerId 注册（S3.8，标准多实例形态）：
 * Redis 租约（SET NX + owner token + TTL）占据 0~31 号 worker；运行时按节拍续租；
 * 续租失败（Redis 故障/租约被抢）只 error 告警——`doc/pitfalls` 记录该降级窗口的重复 ID 风险，
 * 不放宽为静默继续。实例退出释放租约。
 */
@Slf4j
@Component
public class WorkerIdRegistry {

    private static final int MAX_WORKERS = 32;

    /** 续租 LUA：持有者一致才续期 */
    private static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[2]) return 1 end return 0",
            Long.class);

    /** 释放 LUA：持有者一致才删除 */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final Duration leaseTtl;
    private final String owner = UUID.randomUUID().toString().replace("-", "");

    private volatile Integer leasedWorkerId;

    public WorkerIdRegistry(StringRedisTemplate stringRedisTemplate,
                            @Value("${swap.id.worker-lease-seconds:120}") long leaseSeconds) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.leaseTtl = Duration.ofSeconds(leaseSeconds);
    }

    /** 申请 workerId（0~31 第一个空闲者）；无空闲/Redis 不可用即抛（fail-fast）。
     *  由 SnowflakeIdGenerator 装配时调用（不提前到 @PostConstruct：启动失败不留孤儿租约）。 */
    public int lease() {
        for (int id = 0; id < MAX_WORKERS; id++) {
            String key = key(id);
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, owner, leaseTtl);
            if (Boolean.TRUE.equals(acquired)) {
                leasedWorkerId = id;
                log.info("[ID] workerId 租约获取成功 workerId={} ttl={}s", id, leaseTtl.toSeconds());
                return id;
            }
        }
        throw new IllegalStateException("Snowflake workerId 已用尽（0~" + (MAX_WORKERS - 1)
                + "），禁止启动（多实例 ID 冲突风险）");
    }

    @Scheduled(fixedDelayString = "${swap.id.worker-renew-interval-ms:30000}")
    public void renew() {
        Integer id = leasedWorkerId;
        if (id == null) {
            return;
        }
        try {
            Long renewed = stringRedisTemplate.execute(RENEW_SCRIPT, Collections.singletonList(key(id)),
                    owner, String.valueOf(leaseTtl.toMillis()));
            if (renewed == null || renewed == 0) {
                log.error("[ID] workerId 租约续期失败（持有者不匹配/键丢失）workerId={}"
                        + "——继续发号存在跨实例重复风险，需人工介入", id);
            }
        } catch (RuntimeException e) {
            log.error("[ID] workerId 租约续期异常 workerId={} cause={}", id, e.getMessage());
        }
    }

    @PreDestroy
    public void release() {
        Integer id = leasedWorkerId;
        if (id == null) {
            return;
        }
        try {
            stringRedisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(key(id)), owner);
            log.info("[ID] workerId 租约已释放 workerId={}", id);
        } catch (RuntimeException e) {
            log.warn("[ID] workerId 租约释放异常（TTL 自动过期）workerId={} cause={}", id, e.getMessage());
        }
    }

    public Integer leasedWorkerId() {
        return leasedWorkerId;
    }

    private String key(int id) {
        return SwapRedisKeys.ID_WORKER_PREFIX + id;
    }
}
