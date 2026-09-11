package com.swapops.server.common.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 设备下行韧性护栏（S3.8 WP3，标准实现）：
 * <ul>
 *   <li>Bulkhead（信号量，最外层）：并发下行数封顶，慢了/满了一律快速失败（不排队拖死 Tomcat 线程）；</li>
 *   <li>CircuitBreaker（内层）：设备大面积不可用时打开，后续调用直接走 fallback（不再打设备/不再占线程）；</li>
 *   <li>超时：本调用链为同步 HTTP，超时由 socket 层（connect/read timeout）硬保证——
 *       Resilience4j TimeLimiter 只支持 CompletionStage，同步路径用 socket 超时是标准做法（文档已声明）。</li>
 * </ul>
 * 降级语义：熔断/舱壁触发 → 执行 fallback（不执行动作），由调用方决定"抛业务异常"或"返回 null 走对账"。
 * 配置：`resilience4j.circuitbreaker.instances.deviceDownlink` / `...bulkhead...`（application.yml）。
 */
@Slf4j
@Component
public class DeviceDownlinkGuard {

    public static final String INSTANCE = "deviceDownlink";

    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public DeviceDownlinkGuard(CircuitBreakerRegistry circuitBreakerRegistry,
                               BulkheadRegistry bulkheadRegistry) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE);
        this.bulkhead = bulkheadRegistry.bulkhead(INSTANCE);
    }

    /**
     * 执行受护调用（同步）。
     *
     * @param action   真实调用（失败会被熔断器计数并原样抛出）
     * @param fallback 熔断/舱壁拒绝时的降级结果（不执行 action）
     */
    public <T> T call(Supplier<T> action, Supplier<T> fallback) {
        // Bulkhead 在外、CB 在内：舱壁拒绝不计入熔断失败率（区分"过载"与"设备故障"）
        Callable<T> decorated = Bulkhead.decorateCallable(bulkhead,
                CircuitBreaker.decorateCallable(circuitBreaker, action::get));
        try {
            return decorated.call();
        } catch (CallNotPermittedException e) {
            log.warn("[resilience] 熔断打开，快速失败降级 instance={} state={}", INSTANCE, circuitBreaker.getState());
            return fallback.get();
        } catch (BulkheadFullException e) {
            log.warn("[resilience] 舱壁已满，快速失败降级 instance={} availablePermits={}",
                    INSTANCE, bulkhead.getMetrics().getAvailableConcurrentCalls());
            return fallback.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("设备下行调用异常", e);
        }
    }

    /** 供运维/测试读取当前状态 */
    public CircuitBreaker.State state() {
        return circuitBreaker.getState();
    }
}
