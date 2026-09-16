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

import java.net.InetAddress;
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
    /** 稳定实例标识（ip:port，P1-10）：同实例重启/RDB 回退后仍能识别"自己的旧租约"；
     *  跨机/跨端口冲突语义不变（owner 不同仍拒绝，不静默顶掉）。 */
    private final String owner;

    private volatile Integer leasedWorkerId;

    public WorkerIdRegistry(StringRedisTemplate stringRedisTemplate,
                            @Value("${swap.id.worker-lease-seconds:120}") long leaseSeconds,
                            @Value("${server.port:0}") int serverPort) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.leaseTtl = Duration.ofSeconds(leaseSeconds);
        this.owner = instanceId(serverPort);
    }

    static String instanceId(int serverPort) {
        try {
            return InetAddress.getLocalHost().getHostAddress() + ":" + serverPort;
        } catch (Exception e) {
            return "unknown:" + serverPort;
        }
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
            // P1-10 混沌修复：键被"本实例"持有（强杀重启未释放 / Redis 回退到本机旧键）→ 直接夺回续期；
            // 他实例持有（owner 不同）→ 跳过，跨机冲突语义不变。
            String current = stringRedisTemplate.opsForValue().get(key);
            if (owner.equals(current)) {
                stringRedisTemplate.opsForValue().set(key, owner, leaseTtl);
                leasedWorkerId = id;
                log.info("[ID] workerId 租约夺回（本实例旧租约）workerId={}", id);
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
