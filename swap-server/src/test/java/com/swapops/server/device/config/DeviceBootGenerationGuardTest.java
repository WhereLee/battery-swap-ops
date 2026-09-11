package com.swapops.server.device.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 代际重放守卫单测（S3.1）：稳态直通、新代际放行、旧代际拒绝、Redis 异常 fail-open、登记幂等。
 */
@DisplayName("柜代际重放守卫（S3.1）")
@ExtendWith(MockitoExtension.class)
class DeviceBootGenerationGuardTest {

    private static final String CABINET = "SWAP-C-001";
    private static final String CURRENT_KEY = BarrierKeys.current(CABINET);
    private static final String HISTORY_KEY = BarrierKeys.history(CABINET);

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SetOperations<String, String> setOperations;
    @InjectMocks
    private DeviceBootGenerationGuard guard;

    /** 键名引用（与实现同一来源，防拼写漂移） */
    private static final class BarrierKeys {
        static String current(String cabinetNo) {
            return SwapRedisKeys.BOOT_CURRENT_PREFIX + cabinetNo;
        }

        static String history(String cabinetNo) {
            return SwapRedisKeys.BOOT_HISTORY_PREFIX + cabinetNo;
        }
    }

    @Test
    @DisplayName("同代际（稳态）：直通，不查历史集合")
    void 稳态直通() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CURRENT_KEY)).thenReturn("boot-1");

        assertThat(guard.isReplay(CABINET, "boot-1")).isFalse();
        verify(stringRedisTemplate, never()).opsForSet();
    }

    @Test
    @DisplayName("首次上报（无当前代际）：直通")
    void 无当前代际直通() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CURRENT_KEY)).thenReturn(null);

        assertThat(guard.isReplay(CABINET, "boot-1")).isFalse();
    }

    @Test
    @DisplayName("换代际且历史未见：放行（真重启新代际）")
    void 新代际放行() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CURRENT_KEY)).thenReturn("boot-2");
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(HISTORY_KEY, "boot-3")).thenReturn(false);

        assertThat(guard.isReplay(CABINET, "boot-3")).isFalse();
    }

    @Test
    @DisplayName("旧代际历史命中：判定重放（调用方拒绝）")
    void 旧代际拒绝() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CURRENT_KEY)).thenReturn("boot-2");
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(HISTORY_KEY, "boot-1")).thenReturn(true);

        assertThat(guard.isReplay(CABINET, "boot-1")).isTrue();
    }

    @Test
    @DisplayName("Redis 异常：fail-open 放行（不阻断上报生命线）")
    void redis异常放行() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CURRENT_KEY)).thenThrow(new RuntimeException("redis down"));

        assertThat(guard.isReplay(CABINET, "boot-9")).isFalse();
    }

    @Test
    @DisplayName("register 首写：SET 当前代际 + SADD 历史 + 设 TTL（30 天）")
    void register首写() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.add(HISTORY_KEY, "boot-1")).thenReturn(1L);

        guard.register(CABINET, "boot-1");

        verify(valueOperations).set(CURRENT_KEY, "boot-1");
        verify(stringRedisTemplate).expire(HISTORY_KEY, 30L, TimeUnit.DAYS);
    }

    @Test
    @DisplayName("register 重复登记：SADD 返回 0，不重设 TTL")
    void register重复() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.add(HISTORY_KEY, "boot-1")).thenReturn(0L);

        guard.register(CABINET, "boot-1");

        verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class));
    }

    @Test
    @DisplayName("register Redis 异常：不抛（加固层不反噬事件主链路）")
    void register异常不抛() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(CURRENT_KEY, "boot-1");

        org.assertj.core.api.Assertions.assertThatCode(() -> guard.register(CABINET, "boot-1"))
                .doesNotThrowAnyException();
    }
}
