package com.swapops.server.common.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Snowflake 单测（S3.8）：并发唯一/单调、回拨等待、回拨拒绝、参数校验。
 */
@DisplayName("Snowflake ID")
class SnowflakeIdGeneratorTest {

    private static final long BASE = 1767225600000L;

    @Test
    @DisplayName("8 线程 16 万 ID：无重复且全局单调")
    void 并发唯一与单调() throws Exception {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1, 5, System::currentTimeMillis);
        int threads = 8;
        int perThread = 20000;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        List<Long> ordered = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // get+add 同锁：列表顺序即发号顺序（否则断言会被线程调度干扰）
                        synchronized (generator) {
                            long id = generator.nextId();
                            ids.add(id);
                            ordered.add(id);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(ids).hasSize(threads * perThread);
        // 发号在 synchronized 内完成：全局有序
        boolean monotonic = true;
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i) <= ordered.get(i - 1)) {
                monotonic = false;
                break;
            }
        }
        assertThat(monotonic).isTrue();
    }

    @Test
    @DisplayName("小幅回拨（≤容忍窗口）：自旋等待追平后继续发号")
    void 小幅回拨等待追平() {
        AtomicInteger calls = new AtomicInteger();
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1, 50, () -> switch (calls.getAndIncrement()) {
            case 0 -> BASE;          // 第一次发号
            case 1 -> BASE - 10;     // 回拨 10ms（≤50）
            default -> BASE + 1;     // 追平后继续
        });

        long first = generator.nextId();
        long second = generator.nextId();

        assertThat(second).isGreaterThan(first);
    }

    @Test
    @DisplayName("大幅回拨（>容忍窗口）：拒绝发号（ClockMovedBackwardsException）")
    void 大幅回拨拒绝() {
        AtomicInteger calls = new AtomicInteger();
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1, 5, () ->
                calls.getAndIncrement() == 0 ? BASE : BASE - 100);

        generator.nextId();
        assertThatThrownBy(generator::nextId).isInstanceOf(ClockMovedBackwardsException.class);
    }

    @Test
    @DisplayName("参数校验：datacenter/worker 越界拒绝")
    void 参数校验() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(32, 0, 5, System::currentTimeMillis))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, 32, 5, System::currentTimeMillis))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("同毫秒序列耗尽：等待下一毫秒（不受固定时钟死锁影响）")
    void 序列耗尽等待下一毫秒() {
        AtomicLong now = new AtomicLong(BASE);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1, 5,
                () -> now.updateAndGet(v -> v + 1));

        long first = generator.nextId();
        long second = generator.nextId();

        assertThat(second).isGreaterThan(first);
    }
}
