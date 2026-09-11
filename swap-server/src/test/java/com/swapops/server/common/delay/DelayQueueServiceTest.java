package com.swapops.server.common.delay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 延迟队列单测（S3.3）：入队/原子领取/取消残留/退避/死信。
 */
@DisplayName("延迟任务队列（ZSET+HASH）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DelayQueueServiceTest {

    private static final String Z = "swap:delay:z:t";
    private static final String P = "swap:delay:p:t";
    private static final String A = "swap:delay:a:t";
    private static final String DEAD = "swap:delay:dead:t";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ZSetOperations<String, String> zset;
    @Mock
    private HashOperations<String, Object, Object> hash;
    @Mock
    private com.swapops.server.alarm.service.AlarmService alarmService;

    private DelayProperties properties;
    private DelayQueueService service;

    @BeforeEach
    void setUp() {
        properties = new DelayProperties();
        service = new DelayQueueService(redis, properties, alarmService);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hash);
    }

    @Test
    @DisplayName("入队：载荷+计数重置+ZSET score=执行时刻")
    void 入队() {
        service.enqueue("t", "SWO-1", "{\"orderNo\":\"SWO-1\"}", 12345L);

        verify(hash).put(P, "SWO-1", "{\"orderNo\":\"SWO-1\"}");
        verify(hash).put(A, "SWO-1", "0");
        verify(zset).add(Z, "SWO-1", 12345L);
    }

    @Test
    @DisplayName("领取：ZREM=1 原子占有；ZREM=0（他实例已领）跳过")
    void 领取原子竞争() {
        when(zset.rangeByScore(eq(Z), eq(0.0), anyDouble(), eq(0L), eq(100L)))
                .thenReturn(new LinkedHashSet<>(List.of("SWO-1", "SWO-2")));
        when(zset.remove(Z, "SWO-1")).thenReturn(1L);
        when(zset.remove(Z, "SWO-2")).thenReturn(0L);
        when(hash.get(P, "SWO-1")).thenReturn("{\"orderNo\":\"SWO-1\"}");
        when(hash.get(A, "SWO-1")).thenReturn("2");

        List<DelayQueueService.DelayTask> tasks = service.claim("t", 999L, 100);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).taskId()).isEqualTo("SWO-1");
        assertThat(tasks.get(0).attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("领取：载荷缺失（已取消残留）跳过")
    void 取消残留跳过() {
        when(zset.rangeByScore(eq(Z), eq(0.0), anyDouble(), eq(0L), eq(100L)))
                .thenReturn(new LinkedHashSet<>(List.of("SWO-1")));
        when(zset.remove(Z, "SWO-1")).thenReturn(1L);
        when(hash.get(P, "SWO-1")).thenReturn(null);

        assertThat(service.claim("t", 999L, 100)).isEmpty();
    }

    @Test
    @DisplayName("失败退避：计数+1 且重排到 now+base×N")
    void 失败退避() {
        DelayQueueService.DelayTask task = new DelayQueueService.DelayTask("t", "SWO-1", "{}", 0);
        long before = System.currentTimeMillis();
        service.fail(task, "boom");

        verify(hash).put(A, "SWO-1", "1");
        verify(zset).add(eq(Z), eq("SWO-1"), anyDouble());
        verify(hash, never()).put(eq(DEAD), anyString(), anyString());
    }

    @Test
    @DisplayName("超限：移入死信并清活跃载荷")
    void 超限移死信() {
        DelayQueueService.DelayTask task = new DelayQueueService.DelayTask("t", "SWO-1", "{}", 2);
        service.fail(task, "boom");

        verify(hash).put(DEAD, "SWO-1", "{}");
        verify(hash).delete(P, "SWO-1");
        verify(hash).delete(A, "SWO-1");
        verify(zset, never()).add(eq(Z), anyString(), anyDouble());
    }
}
