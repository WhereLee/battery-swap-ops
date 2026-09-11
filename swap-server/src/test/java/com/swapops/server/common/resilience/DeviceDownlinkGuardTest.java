package com.swapops.server.common.resilience;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 下行护栏单测（S3.8 WP3）：熔断打开/快速失败、成功不误触发、舱壁满降级、自动半开恢复。
 */
@DisplayName("设备下行护栏（熔断+舱壁）")
class DeviceDownlinkGuardTest {

    private DeviceDownlinkGuard guard(CircuitBreakerConfig cb, BulkheadConfig bh) {
        return new DeviceDownlinkGuard(CircuitBreakerRegistry.of(cb), BulkheadRegistry.of(bh));
    }

    private CircuitBreakerConfig cbConfig(Duration waitOpen, int window, int minCalls) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(window)
                .minimumNumberOfCalls(minCalls)
                .failureRateThreshold(50)
                .waitDurationInOpenState(waitOpen)
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }

    private BulkheadConfig bhConfig(int maxConcurrent, Duration maxWait) {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(maxWait)
                .build();
    }

    @Test
    @DisplayName("失败率达阈值：熔断打开，后续调用直接降级且不再执行动作")
    void 熔断打开与降级() {
        DeviceDownlinkGuard guard = guard(cbConfig(Duration.ofSeconds(60), 4, 2), bhConfig(4, Duration.ZERO));
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Supplier<Integer> failing = () -> {
            calls.incrementAndGet();
            throw new RuntimeException("device down");
        };

        // 动作失败：异常原样抛出（已计入熔断），fallback 只用于熔断/舱壁拒绝
        assertThatThrownBy(() -> guard.call(failing, () -> -1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> guard.call(failing, () -> -1)).isInstanceOf(RuntimeException.class);
        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.OPEN);

        int before = calls.get();
        assertThat(guard.call(failing, () -> -1)).isEqualTo(-1);
        assertThat(calls.get()).isEqualTo(before); // 熔断：动作未执行
    }

    @Test
    @DisplayName("成功调用：状态保持 CLOSED，返回值透传")
    void 成功不误触发() {
        DeviceDownlinkGuard guard = guard(cbConfig(Duration.ofSeconds(60), 4, 2), bhConfig(4, Duration.ZERO));

        assertThat(guard.call(() -> 7, () -> -1)).isEqualTo(7);
        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("舱壁满：快速降级（不执行动作），释放后恢复")
    void 舱壁满降级() throws Exception {
        DeviceDownlinkGuard guard = guard(cbConfig(Duration.ofSeconds(60), 4, 2), bhConfig(1, Duration.ZERO));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> guard.call(() -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 1;
        }, () -> 0));
        holder.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(guard.call(() -> 2, () -> -1)).isEqualTo(-1);
        release.countDown();
        holder.join(3000);

        assertThat(guard.call(() -> 3, () -> -1)).isEqualTo(3);
    }

    @Test
    @DisplayName("自动半开：等待窗口后放行探测，成功即恢复 CLOSED")
    void 自动半开恢复() throws Exception {
        DeviceDownlinkGuard guard = guard(cbConfig(Duration.ofMillis(100), 2, 1), bhConfig(4, Duration.ZERO));

        assertThatThrownBy(() -> guard.call(() -> {
            throw new RuntimeException("boom");
        }, () -> -1)).isInstanceOf(RuntimeException.class);
        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(250);
        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(guard.call(() -> 9, () -> -1)).isEqualTo(9);
        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
