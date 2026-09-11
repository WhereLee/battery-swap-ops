package com.swapops.server.common.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 死锁重试单测（S3.8 WP7）：死锁类异常重试至成功、业务异常不重试、重试耗尽抛原异常。
 */
@DisplayName("死锁重试标准件")
class DeadlockRetryExecutorTest {

    private final DeadlockRetryExecutor executor = new DeadlockRetryExecutor();

    @Test
    @DisplayName("死锁异常：重试后成功（3 次内）")
    void 死锁重试成功() {
        AtomicInteger attempts = new AtomicInteger();
        String result = executor.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new DeadlockLoserDataAccessException("1213 deadlock", null);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("锁等待超时：同样重试")
    void 锁等待重试() {
        AtomicInteger attempts = new AtomicInteger();
        executor.execute(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw new CannotAcquireLockException("1205 lock wait timeout");
            }
        });

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("业务异常：不重试，原样抛出")
    void 业务异常不重试() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("业务失败");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("死锁持续：重试耗尽后抛原异常（不超过 3 次）")
    void 重试耗尽() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(() -> {
            attempts.incrementAndGet();
            throw new DeadlockLoserDataAccessException("1213", null);
        })).isInstanceOf(DeadlockLoserDataAccessException.class);
        assertThat(attempts.get()).isEqualTo(3);
    }
}
