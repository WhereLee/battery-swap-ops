package com.swapops.server.outbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * outbox 中继参数（swap.outbox.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.outbox")
public class OutboxProperties {

    /** 中继轮询间隔毫秒 */
    private long relayIntervalMs = 5000;

    /** 单轮投递上限 */
    private int batch = 50;

    /** 重试上限（达到后移入 DEAD + 告警） */
    private int maxAttempts = 5;

    /** 退避基数毫秒（第 N 次失败后延迟 N × base） */
    private long backoffBaseMillis = 5000;
}
