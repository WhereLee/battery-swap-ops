package com.swapops.server.concurrency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JVM 并发坑位演示（S3.8 WP9，确定性用例；仅测试域，不产生产代码）：
 * ABA / ThreadLocal 不复位 / 正确 DCL。SimpleDateFormat、无界队列等不可确定性复现的坑在知识档说明。
 */
@DisplayName("JVM 并发坑位演示")
class ConcurrencyPitfallsDemoTest {

    @Test
    @DisplayName("ABA：AtomicInteger 的 CAS 会误判；AtomicStampedReference 能识别")
    void aba问题() {
        // 场景：线程A读到 100；线程B依次改成 200、又改回 100；线程A用旧期望值 CAS(100→50) 成功——ABA 未被察觉
        AtomicInteger plain = new AtomicInteger(100);
        int expected = plain.get();
        plain.compareAndSet(100, 200);
        plain.compareAndSet(200, 100);
        boolean wronglySucceeded = plain.compareAndSet(expected, 50);
        assertThat(wronglySucceeded).isTrue();
        assertThat(plain.get()).isEqualTo(50);

        // 带版本戳：B 的两次修改让 stamp 从 0→2；A 持旧 stamp=0 的 CAS 必然失败
        AtomicStampedReference<Integer> stamped = new AtomicStampedReference<>(100, 0);
        int[] stampHolder = new int[1];
        Integer expectedValue = stamped.get(stampHolder);
        int expectedStamp = stampHolder[0];
        stamped.compareAndSet(100, 200, 0, 1);
        stamped.compareAndSet(200, 100, 1, 2);
        boolean detected = stamped.compareAndSet(expectedValue, 50, expectedStamp, expectedStamp + 1);
        assertThat(detected).isFalse();
        assertThat(stamped.getReference()).isEqualTo(100);
    }

    @Test
    @DisplayName("ThreadLocal 不复位：线程复用会串号（所以 UserContext/MDC 必须 finally 清理）")
    void threadLocal串号() {
        ThreadLocal<String> requestUser = new ThreadLocal<>();
        requestUser.set("user-A");
        // 模拟"忘记 remove"后，同一个工作线程处理下一个请求
        String leaked = requestUser.get();
        assertThat(leaked).isEqualTo("user-A");

        requestUser.remove();
        assertThat(requestUser.get()).isNull();
    }

    @Test
    @DisplayName("MDC 同理：跨线程不自动继承（本项目用 TaskDecorator 显式传递）")
    void mdc不跨线程() throws Exception {
        MDC.put("traceId", "trace-main");
        AtomicReference<String> inChild = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread child = new Thread(() -> {
            inChild.set(MDC.get("traceId"));
            done.countDown();
        });
        child.start();
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(inChild.get()).isNull();          // 子线程看不到（需显式传递）
        assertThat(MDC.get("traceId")).isEqualTo("trace-main");
        MDC.clear();
    }

    @Test
    @DisplayName("正确 DCL：volatile + 二次检查（本项目 ensureProducer 即此形态）")
    void 正确DCL() throws Exception {
        class Lazy {
            private volatile Object instance;

            Object get() {
                Object local = instance;
                if (local == null) {
                    synchronized (this) {
                        local = instance;
                        if (local == null) {
                            local = new Object();
                            instance = local;
                        }
                    }
                }
                return local;
            }
        }
        Lazy lazy = new Lazy();
        AtomicReference<Object> a = new AtomicReference<>();
        AtomicReference<Object> b = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread t1 = new Thread(() -> {
            await(start);
            a.set(lazy.get());
        });
        Thread t2 = new Thread(() -> {
            await(start);
            b.set(lazy.get());
        });
        t1.start();
        t2.start();
        start.countDown();
        t1.join(3000);
        t2.join(3000);

        assertThat(a.get()).isNotNull().isSameAs(b.get());
        assertThat(a.get()).isSameAs(lazy.instance);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
