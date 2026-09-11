package com.swapops.server.common.delay;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 延迟任务队列配置（swap.delay.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.delay")
public class DelayProperties {

    /** 轮询间隔毫秒（精度与 Redis 压力的折中：1s 足够订单级超时） */
    private long pollIntervalMs = 1000;

    /** 单主题单轮领取上限 */
    private int batch = 100;

    /** 失败重试上限（达到后移入死信，S3.6 告警） */
    private int maxAttempts = 3;

    /** 退避基数毫秒（第 N 次失败后延迟 N × base 再试） */
    private long backoffBaseMillis = 5000;
}
