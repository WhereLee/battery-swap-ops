package com.swapops.server.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 应用线程池基线（S3.8 WP4）：
 * <ul>
 *   <li>有界队列 + CallerRunsPolicy：池满时由调用线程执行（背压而非丢弃/无限堆积）；
 *       拒绝对业务静默丢任务——队列满 = 直接拖慢上游，问题立刻可见；</li>
 *   <li>命名线程（swap-async-）+ MDC traceId 传递（异步日志可串联同一请求）；</li>
 *   <li>Micrometer 指标绑定（swap.application.executor.*），与 Tomcat/Hikari 指标同看板。</li>
 * </ul>
 * 池大小基线：Tomcat 线程(100) > 应用池(16) ≥ DB 连接池(20 中含调度/对账占用)，
 * 保证"平台忙"时先背压而不是把连接池打爆（雪崩链条第一环）。
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    @Bean
    public ThreadPoolTaskExecutor applicationTaskExecutor(ObjectProvider<MeterRegistry> meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("swap-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(mdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();

        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            ExecutorServiceMetrics.monitor(registry, executor.getThreadPoolExecutor(), "swap.application.executor");
        }
        log.info("[线程池] 应用池就绪 core={} max={} queue={} 拒绝策略=CallerRuns",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    /** 异步任务继承请求 traceId（MDC 不自动跨线程） */
    private TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            String traceId = MDC.get("traceId");
            return () -> {
                if (traceId != null) {
                    MDC.put("traceId", traceId);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.remove("traceId");
                }
            };
        };
    }
}
