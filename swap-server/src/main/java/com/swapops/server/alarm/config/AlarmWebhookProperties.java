package com.swapops.server.alarm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 告警出站 webhook 参数（swap.alarm.webhook.*，P1-11）。
 * url 为空则整体禁用（本地/CI 默认关闭）；secret 仅环境变量注入（仓库零明文）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.alarm.webhook")
public class AlarmWebhookProperties {

    /** 出站回调 URL；为空=禁用（通知静默丢弃，告警主链路不受影响） */
    private String url = "";

    /** HMAC-SHA256 签名密钥（X-Swap-Sign = hex(HMAC(secret, body))）；为空则不签名 */
    private String secret = "";

    /** 连接超时毫秒（外部系统僵死不得拖住发送线程） */
    private int connectTimeoutMillis = 1000;

    /** 单次请求超时毫秒 */
    private int requestTimeoutMillis = 3000;

    /** 单事件最大尝试次数（含首次） */
    private int maxAttempts = 3;

    /** 重试退避基数毫秒（第 n 次失败后退避 backoffBase × n） */
    private long backoffBaseMillis = 500;

    /** 发送队列容量（满则丢弃并计数，绝不阻塞告警主链路） */
    private int queueCapacity = 1000;

    /** 连续失败熔断阈值（一次事件全部重试失败记一次失败） */
    private int circuitFailThreshold = 5;

    /** 熔断打开时长毫秒（期间通知直接丢弃） */
    private long circuitOpenMillis = 60000;
}
