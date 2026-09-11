package com.swapops.server.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 应用线程池基线单测（S3.8 WP4）：有界队列/CallerRuns 背压/MDC 传递/池上限。
 */
@DisplayName("应用线程池基线")
class ThreadPoolConfigTest {

    private ThreadPoolTaskExecutor executor;

    @SuppressWarnings("unchecked")
    private ThreadPoolTaskExecutor newExecutor() {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new ThreadPoolConfig().applicationTaskExecutor(provider);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("配置基线：有界队列 + CallerRuns + 命名前缀")
    void 配置基线() {
        executor = newExecutor();

        assertThat(executor.getCorePoolSize()).isEqualTo(8);
        assertThat(executor.getMaxPoolSize()).isEqualTo(16);
        assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(200);
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("swap-async-");
    }

    @Test
    @DisplayName("池满（core+queue+max 全占）：新任务在调用线程执行（背压不丢弃）")
    void 池满背压CallerRuns() throws Exception {
        executor = newExecutor();
        CountDownLatch release = new CountDownLatch(1);
        int capacity = executor.getCorePoolSize()
                + executor.getThreadPoolExecutor().getQueue().remainingCapacity()
                + (executor.getMaxPoolSize() - executor.getCorePoolSize());
        for (int i = 0; i < capacity; i++) {
            executor.execute(() -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 等线程池把队列灌满（提交完成即可，CallerRuns 只发生在饱和后）
        Thread.sleep(200);

        AtomicReference<String> threadName = new AtomicReference<>();
        executor.execute(() -> threadName.set(Thread.currentThread().getName()));

        assertThat(threadName.get()).isEqualTo(Thread.currentThread().getName());
        release.countDown();
    }

    @Test
    @DisplayName("MDC traceId 跨线程传递")
    void MDC传递() throws Exception {
        executor = newExecutor();
        MDC.put("traceId", "trace-abc");
        AtomicReference<String> captured = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            captured.set(MDC.get("traceId"));
            done.countDown();
        });

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get()).isEqualTo("trace-abc");
        assertThat(MDC.get("traceId")).isEqualTo("trace-abc"); // 调用线程不受影响
    }
}
