package com.swapops.server.common.retry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 死锁重试标准件（S3.8 WP7）：
 * <ul>
 *   <li>只重试**数据库死锁/锁等待**类异常（MySQL 1213/1205 映射的 Spring 异常）——业务异常不重试；</li>
 *   <li>最多 3 次、指数退避 100ms×2（上限 1s）：死锁窗口极短，重试几乎必然成功；</li>
 *   <li>前提：动作必须幂等（唯一键/CAS/条件更新）——本项目所有可重试动作都满足。</li>
 * </ul>
 * 说明：MySQL 死锁是正常并发现象（InnoDB 主动回滚代价小的一方），正确姿势是"一致加锁顺序 + 有界重试"，
 * 而不是把死锁当异常报警处理。
 */
@Slf4j
@Component
public class DeadlockRetryExecutor {

    private final RetryTemplate retryTemplate;

    public DeadlockRetryExecutor() {
        this.retryTemplate = RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(100, 2, 1000)
                .retryOn(DeadlockLoserDataAccessException.class)
                .retryOn(CannotAcquireLockException.class)
                .build();
    }

    public <T> T execute(Supplier<T> action) {
        return retryTemplate.execute(context -> {
            if (context.getRetryCount() > 0) {
                log.warn("[retry] 死锁/锁等待重试第 {} 次", context.getRetryCount());
            }
            return action.get();
        });
    }

    public void execute(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }
}
