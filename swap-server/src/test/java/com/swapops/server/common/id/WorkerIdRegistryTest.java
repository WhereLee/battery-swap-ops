package com.swapops.server.common.id;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * workerId 租约注册单测（S3.8）：首个空闲/用尽拒绝/续租/释放。
 */
@DisplayName("Snowflake workerId 租约")
@ExtendWith(MockitoExtension.class)
class WorkerIdRegistryTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private WorkerIdRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkerIdRegistry(redis, 120);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("取第一个空闲 workerId（0 被占则取 1）")
    void 首个空闲() {
        when(valueOps.setIfAbsent(eq("swap:id:worker:0"), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.setIfAbsent(eq("swap:id:worker:1"), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(registry.lease()).isEqualTo(1);
        assertThat(registry.leasedWorkerId()).isEqualTo(1);
    }

    @Test
    @DisplayName("workerId 用尽：拒绝启动")
    void 用尽拒绝() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> registry.lease())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已用尽");
    }

    @Test
    @DisplayName("续租：持有者不一致时告警但不崩溃（租约到期风险留痕）")
    void 续租持有者不一致() {
        when(valueOps.setIfAbsent(eq("swap:id:worker:0"), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        registry.lease();

        registry.renew();

        assertThat(registry.leasedWorkerId()).isEqualTo(0);
    }

    @Test
    @DisplayName("释放：LUA 比对持有者后删除")
    void 释放() {
        when(valueOps.setIfAbsent(eq("swap:id:worker:0"), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
        registry.lease();

        registry.release();

        assertThat(registry.leasedWorkerId()).isEqualTo(0);
    }
}
