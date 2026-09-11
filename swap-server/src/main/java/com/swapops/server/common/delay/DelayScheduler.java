package com.swapops.server.common.delay;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 延迟任务调度器（S3.3）：按注册的处理器主题轮询领取到期任务并分发。
 * 多实例安全由 {@link DelayQueueService#claim} 的 ZREM 原子竞争保证（无需额外锁）。
 */
@Slf4j
@Component
public class DelayScheduler {

    private final DelayQueueService queue;
    private final DelayProperties properties;
    private final Map<String, DelayTaskHandler> handlers = new LinkedHashMap<>();

    public DelayScheduler(DelayQueueService queue, DelayProperties properties,
                          List<DelayTaskHandler> handlerList) {
        this.queue = queue;
        this.properties = properties;
        for (DelayTaskHandler handler : handlerList) {
            DelayTaskHandler previous = handlers.put(handler.topic(), handler);
            if (previous != null) {
                throw new IllegalStateException("延迟任务主题重复注册: " + handler.topic());
            }
        }
        log.info("延迟任务调度器就绪 topics={}", handlers.keySet());
    }

    @Scheduled(fixedDelayString = "${swap.delay.poll-interval-ms:1000}")
    public void poll() {
        long now = System.currentTimeMillis();
        for (DelayTaskHandler handler : handlers.values()) {
            List<DelayQueueService.DelayTask> tasks;
            try {
                tasks = queue.claim(handler.topic(), now, properties.getBatch());
            } catch (Exception e) {
                // Redis 抖动：本轮跳过（任务留在队列，下轮再领）
                log.warn("延迟任务领取异常（本轮跳过）topic={} cause={}", handler.topic(), e.getMessage());
                continue;
            }
            for (DelayQueueService.DelayTask task : tasks) {
                try {
                    handler.handle(task.payload());
                    queue.complete(task);
                } catch (Exception e) {
                    queue.fail(task, e.getMessage());
                }
            }
        }
    }
}
