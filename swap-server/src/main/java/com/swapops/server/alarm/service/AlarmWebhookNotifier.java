package com.swapops.server.alarm.service;

import com.swapops.server.alarm.config.AlarmWebhookProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警出站 webhook（P1-11）：把告警 RAISED / HANDLED / RECOVERED 事件回调外部运维系统
 * （钉钉/企业微信网关类）。与 swap-alarm MQ 事件（Agent 消费）互补：MQ 面向内部 Agent，
 * webhook 面向外部值班系统。
 *
 * <p>可靠性模型 = 尽力而为 + 快速失败，与告警主链路严格隔离：
 * <ul>
 *   <li>单发送线程 + 有界队列（容量满丢弃计数，绝不阻塞业务线程/事务）；</li>
 *   <li>单事件内联重试（退避 backoffBase × 尝试序号），全部失败记一次"连续失败"；</li>
 *   <li>连续失败达阈值熔断打开（默认 60s 内新事件直接丢弃），成功即清零；</li>
 *   <li>任何异常都被吞掉并计数——外部系统故障不得影响告警产生与平台运行。</li>
 * </ul>
 * 签名：X-Swap-Sign = hex(HMAC-SHA256(secret, body))，secret 为空则不发签名头。
 * 事件类型经 X-Swap-Event 头透传（RAISED/HANDLED/RECOVERED）。
 */
@Slf4j
@Component
public class AlarmWebhookNotifier {

    /** 签名头（与接收端约定） */
    public static final String SIGN_HEADER = "X-Swap-Sign";
    /** 事件类型头 */
    public static final String EVENT_HEADER = "X-Swap-Event";

    private final AlarmWebhookProperties properties;
    private final HttpClient httpClient;
    private final ThreadPoolExecutor executor;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntil = 0L;

    public AlarmWebhookNotifier(AlarmWebhookProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .build();
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity())),
                r -> {
                    Thread t = new Thread(r, "alarm-webhook-sender");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** url 非空才启用 */
    public boolean enabled() {
        return properties.getUrl() != null && !properties.getUrl().isBlank();
    }

    /**
     * 异步出站通知（尽力而为，绝不抛出、绝不阻塞调用方）。
     * 禁用/熔断中/队列满 → 计数丢弃；发送失败永远只影响 webhook 自身。
     */
    public void notify(String eventKind, String envelopeJson) {
        try {
            if (!enabled() || envelopeJson == null) {
                return;
            }
            long openUntil = circuitOpenUntil;
            if (System.currentTimeMillis() < openUntil) {
                dropped.incrementAndGet();
                log.warn("告警 webhook 熔断中（至 {}），丢弃事件 event={}", openUntil, eventKind);
                return;
            }
            executor.execute(() -> deliver(eventKind, envelopeJson));
        } catch (RejectedExecutionException e) {
            dropped.incrementAndGet();
            log.warn("告警 webhook 队列已满（容量 {}），丢弃事件 event={}",
                    properties.getQueueCapacity(), eventKind);
        } catch (RuntimeException e) {
            dropped.incrementAndGet();
            log.warn("告警 webhook 提交异常（忽略） event={} cause={}", eventKind, e.getMessage());
        }
    }

    /** 单线程消费：内联重试 + 退避；最终成败驱动连续失败计数与熔断。 */
    private void deliver(String eventKind, String body) {
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getUrl()))
                        .timeout(Duration.ofMillis(properties.getRequestTimeoutMillis()))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header(EVENT_HEADER, eventKind)
                        .header(SIGN_HEADER, sign(body))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    sent.incrementAndGet();
                    consecutiveFailures.set(0);
                    log.info("告警 webhook 已投递 event={} status={}", eventKind, response.statusCode());
                    return;
                }
                log.warn("告警 webhook 非 2xx（将重试） event={} status={} attempt={}/{}",
                        eventKind, response.statusCode(), attempt, maxAttempts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("告警 webhook 发送异常 attempt={}/{} event={} cause={}",
                        attempt, maxAttempts, eventKind, e.getMessage());
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(properties.getBackoffBaseMillis() * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        failed.incrementAndGet();
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= Math.max(1, properties.getCircuitFailThreshold())) {
            circuitOpenUntil = System.currentTimeMillis() + properties.getCircuitOpenMillis();
            log.error("告警 webhook 连续失败 {} 次，熔断打开 {}ms（期间通知丢弃）",
                    failures, properties.getCircuitOpenMillis());
        }
    }

    /** HMAC-SHA256 签名（hex）；secret 为空/计算失败返回空串（不携带签名头值） */
    private String sign(String body) {
        String secret = properties.getSecret();
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("告警 webhook 签名计算失败（不带签名投递）: {}", e.getMessage());
            return "";
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            long drainMillis = properties.getRequestTimeoutMillis() * Math.max(1, properties.getMaxAttempts()) + 1000L;
            executor.awaitTermination(drainMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 已成功投递事件数 */
    public long sentCount() {
        return sent.get();
    }

    /** 重试耗尽仍失败的事件数 */
    public long failedCount() {
        return failed.get();
    }

    /** 禁用/熔断/队列满导致的丢弃数 */
    public long droppedCount() {
        return dropped.get();
    }
}
