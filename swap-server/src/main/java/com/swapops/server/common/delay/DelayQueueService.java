package com.swapops.server.common.delay;

import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.device.config.SwapRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 延迟任务队列（S3.3，Redis ZSET + HASH，多实例安全）：
 * <ul>
 *   <li>ZSET（score=执行时刻，member=taskId）= 时间轮；HASH = 载荷与重试计数（ZSET member 需短小稳定）；</li>
 *   <li>领取 = 按分数范围取到期集合 → 逐个 ZREM：**ZREM 返回 1 才是本实例领取**（多实例原子竞争）；</li>
 *   <li>取消 = ZREM + HDEL；成功 = 清载荷；失败 = 计数+1 + 退避重排；超上限 = 移入死信 HASH。</li>
 * </ul>
 * 语义为 at-least-once（领取后实例崩溃则任务丢失本轮，由业务扫描任务兜底；重试幂等由处理器保证）。
 */
@Slf4j
@Service
public class DelayQueueService {

    private final StringRedisTemplate stringRedisTemplate;
    private final DelayProperties properties;
    private final AlarmService alarmService;

    public DelayQueueService(StringRedisTemplate stringRedisTemplate, DelayProperties properties,
                             AlarmService alarmService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.alarmService = alarmService;
    }

    /** 到期任务（领取结果） */
    public record DelayTask(String topic, String taskId, String payload, int attempts) {
    }

    /** 入队/重置（同 taskId 覆盖：score 与载荷一并更新，重试计数归 0） */
    public void enqueue(String topic, String taskId, String payload, long executeAtMillis) {
        stringRedisTemplate.opsForHash().put(payloadKey(topic), taskId, payload);
        stringRedisTemplate.opsForHash().put(attemptKey(topic), taskId, "0");
        stringRedisTemplate.opsForZSet().add(zKey(topic), taskId, executeAtMillis);
    }

    /** 取消（不存在时无害） */
    public void cancel(String topic, String taskId) {
        stringRedisTemplate.opsForZSet().remove(zKey(topic), taskId);
        stringRedisTemplate.opsForHash().delete(payloadKey(topic), taskId);
        stringRedisTemplate.opsForHash().delete(attemptKey(topic), taskId);
    }

    /** 领取到期任务（ZREM 原子竞争：多实例下只有一个实例拿到同一 taskId） */
    public List<DelayTask> claim(String topic, long now, int batch) {
        Set<String> candidates = stringRedisTemplate.opsForZSet()
                .rangeByScore(zKey(topic), 0, now, 0, batch);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<DelayTask> tasks = new ArrayList<>();
        for (String taskId : candidates) {
            Long removed = stringRedisTemplate.opsForZSet().remove(zKey(topic), taskId);
            if (removed == null || removed == 0) {
                continue; // 已被其他实例领取或已取消
            }
            Object payload = stringRedisTemplate.opsForHash().get(payloadKey(topic), taskId);
            if (payload == null) {
                continue; // 取消后残留（ZSET 已删，载荷早已清理）
            }
            Object attempts = stringRedisTemplate.opsForHash().get(attemptKey(topic), taskId);
            tasks.add(new DelayTask(topic, taskId, String.valueOf(payload), parseInt(attempts)));
        }
        return tasks;
    }

    /** 处理成功：清载荷与计数 */
    public void complete(DelayTask task) {
        stringRedisTemplate.opsForHash().delete(payloadKey(task.topic()), task.taskId());
        stringRedisTemplate.opsForHash().delete(attemptKey(task.topic()), task.taskId());
    }

    /**
     * 处理失败：计数+1；未达上限 → 退避重排；达上限 → 移入死信（S3.6 告警接管）。
     */
    public void fail(DelayTask task, String cause) {
        int next = task.attempts() + 1;
        if (next >= properties.getMaxAttempts()) {
            stringRedisTemplate.opsForHash().put(deadKey(task.topic()), task.taskId(), task.payload());
            stringRedisTemplate.opsForHash().delete(payloadKey(task.topic()), task.taskId());
            stringRedisTemplate.opsForHash().delete(attemptKey(task.topic()), task.taskId());
            alarmService.raise(AlarmService.DEVICE_SYSTEM, task.topic(), AlarmType.DELAY_DEAD,
                    "延迟任务死信 taskId=" + task.taskId() + " attempts=" + next + " cause=" + cause);
            log.error("延迟任务超限移入死信 topic={} taskId={} attempts={} cause={}",
                    task.topic(), task.taskId(), next, cause);
            return;
        }
        stringRedisTemplate.opsForHash().put(attemptKey(task.topic()), task.taskId(), String.valueOf(next));
        long retryAt = System.currentTimeMillis() + properties.getBackoffBaseMillis() * next;
        stringRedisTemplate.opsForZSet().add(zKey(task.topic()), task.taskId(), retryAt);
        log.warn("延迟任务失败将退避重试 topic={} taskId={} attempts={}/{} cause={}",
                task.topic(), task.taskId(), next, properties.getMaxAttempts(), cause);
    }

    public long size(String topic) {
        Long size = stringRedisTemplate.opsForZSet().size(zKey(topic));
        return size == null ? 0 : size;
    }

    public long deadSize(String topic) {
        Long size = stringRedisTemplate.opsForHash().size(deadKey(topic));
        return size == null ? 0 : size;
    }

    private int parseInt(Object raw) {
        try {
            return raw == null ? 0 : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String zKey(String topic) {
        return SwapRedisKeys.DELAY_Z_PREFIX + topic;
    }

    private String payloadKey(String topic) {
        return SwapRedisKeys.DELAY_PAYLOAD_PREFIX + topic;
    }

    private String attemptKey(String topic) {
        return SwapRedisKeys.DELAY_ATTEMPT_PREFIX + topic;
    }

    private String deadKey(String topic) {
        return SwapRedisKeys.DELAY_DEAD_PREFIX + topic;
    }
}
