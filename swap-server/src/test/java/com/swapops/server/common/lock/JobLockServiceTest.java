package com.swapops.server.common.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务租约锁单测（S3.3）：抢锁执行/未抢跳过/异常释放/Redis 故障降级。
 */
@DisplayName("定时任务租约锁")
@ExtendWith(MockitoExtension.class)
class JobLockServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private JobLockService service;

    @BeforeEach
    void setUp() {
        service = new JobLockService(redis, 60);
    }

    @Test
    @DisplayName("抢到锁：执行并在 finally 释放（LUA 比对持有者）")
    void 抢到锁执行() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean executed = service.runWithLock("order-sweep", () -> ran.set(true));

        assertThat(executed).isTrue();
        assertThat(ran).isTrue();
        verify(redis).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("未抢到锁：跳过本轮，不执行不释放")
    void 未抢到跳过() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean executed = service.runWithLock("order-sweep", () -> ran.set(true));

        assertThat(executed).isFalse();
        assertThat(ran).isFalse();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("任务异常：照常抛出，锁仍释放")
    void 异常仍释放() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.runWithLock("order-sweep", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        verify(redis).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("Redis 故障：降级直接执行（不可停扫，任务幂等）")
    void redis故障降级() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean executed = service.runWithLock("order-sweep", () -> ran.set(true));

        assertThat(executed).isTrue();
        assertThat(ran).isTrue();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }
}
