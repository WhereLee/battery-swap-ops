package com.swapops.server.common.delay;

/**
 * 延迟任务处理器：按 topic 注册，由 {@link DelayScheduler} 在到期时回调。
 * 处理器必须幂等（at-least-once：重试/多实例竞争下可能重复执行）。
 */
public interface DelayTaskHandler {

    /** 任务主题（ZSET 命名空间） */
    String topic();

    /** 处理载荷（payloadJSON） */
    void handle(String payload);
}
