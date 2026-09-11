package com.swapops.server.common.id;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * Snowflake ID（标准实现，S3.8）：
 * <pre>
 * 1 符号 | 41 毫秒时间戳（自定义 epoch） | 5 datacenterId | 5 workerId | 12 序列
 * 容量：单 worker 4096/ms；时间跨度 ~69 年；workerId 经 Redis 租约注册（多实例唯一）。
 * </pre>
 * 时钟回拨策略：≤ tolerance(默认 5ms) 自旋等待追平；超过则抛 {@link ClockMovedBackwardsException}
 * （拒绝生成而非冒险重复——宁可失败可见，不可 ID 冲突）。
 * Clock 通过 {@link LongSupplier} 注入，回拨路径可测。
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    /** 自定义 epoch：2026-01-01T00:00:00Z（缩小时间戳占用，延长可用年限） */
    private static final long EPOCH = 1767225600000L;

    private static final int WORKER_BITS = 5;
    private static final int DATACENTER_BITS = 5;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;
    private static final long MAX_DATACENTER_ID = (1L << DATACENTER_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    private final long workerId;
    private final long datacenterId;
    private final long rollbackToleranceMillis;
    private final LongSupplier clock;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    /** Spring 装配：workerId 由 Redis 租约注册解析（swap.id.worker-id ≥0 时显式指定，联调/测试用） */
    @org.springframework.beans.factory.annotation.Autowired
    public SnowflakeIdGenerator(@Value("${swap.id.datacenter-id:1}") long datacenterId,
                                @Value("${swap.id.worker-id:-1}") long configuredWorkerId,
                                @Value("${swap.id.rollback-tolerance-millis:5}") long rollbackToleranceMillis,
                                WorkerIdRegistry workerIdRegistry) {
        this(datacenterId,
                configuredWorkerId >= 0 ? configuredWorkerId : workerIdRegistry.lease(),
                rollbackToleranceMillis, System::currentTimeMillis);
    }

    /** 测试接缝：显式 worker + 可控时钟 */
    public SnowflakeIdGenerator(long datacenterId, long workerId, long rollbackToleranceMillis,
                                LongSupplier clock) {
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("datacenterId 越界: " + datacenterId);
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 越界: " + workerId);
        }
        this.datacenterId = datacenterId;
        this.workerId = workerId;
        this.rollbackToleranceMillis = rollbackToleranceMillis;
        this.clock = clock;
    }

    public synchronized long nextId() {
        long now = clock.getAsLong();
        if (now < lastTimestamp) {
            long diff = lastTimestamp - now;
            if (diff <= rollbackToleranceMillis) {
                // 小幅回拨：等到追上上一时间戳（自旋，毫秒级）
                while ((now = clock.getAsLong()) <= lastTimestamp) {
                    Thread.onSpinWait();
                }
                log.debug("时钟小幅回拨 {}ms，等待追平后继续发号", diff);
            } else {
                log.error("时钟回拨 {}ms 超过容忍窗口 {}ms，拒绝发号（防 ID 重复）",
                        diff, rollbackToleranceMillis);
                throw new ClockMovedBackwardsException("clock moved backwards " + diff + "ms");
            }
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                now = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << (WORKER_BITS + DATACENTER_BITS + SEQUENCE_BITS))
                | (datacenterId << (WORKER_BITS + SEQUENCE_BITS))
                | (workerId << SEQUENCE_BITS)
                | sequence;
    }

    /** 字符串形态（业务单号拼接用） */
    public String nextIdString() {
        return Long.toString(nextId());
    }

    private long waitNextMillis(long last) {
        long now = clock.getAsLong();
        while (now <= last) {
            Thread.onSpinWait();
            now = clock.getAsLong();
        }
        return now;
    }
}
