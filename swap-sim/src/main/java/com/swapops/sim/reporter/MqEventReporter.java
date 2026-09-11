package com.swapops.sim.reporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.contract.DeviceSignature;
import com.swapops.sim.config.SimProperties;
import com.swapops.sim.config.TraceIds;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MQ 事件上报（S3.5，契约 §7）：
 * <ul>
 *   <li>信封：body=与 HTTP 完全同构的事件 JSON；签名/柜号/traceId 置 message property；keys={cabinetNo}-{eventSeq}；</li>
 *   <li>保序铁律：单发送线程 + 单队列 topic——失败事件**队首持留**（绝不跳过重排，否则后续更大 seq 先到
 *       会被平台序守卫当旧序拒=真丢数据），按 5s 节拍等 broker 恢复；</li>
 *   <li>快速退避 1~5s ×5 → 转"待恢复"持续重试；仅队列满时丢最旧（QUERY_STATE/对账兜底）；</li>
 *   <li>producer 懒重建：broker 未就绪/晚间启动不炸应用，首次发送时才构建。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "swap.sim.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MqEventReporter implements EventReporter {

    /** 快速退避重试上限（1s/2s/3s/4s/5s）；超限转"等恢复"（5s 节拍持续） */
    private static final int MAX_FAST_RETRY = 5;

    /** 关闭时剩余队列尽力补发时间预算（不为补发阻塞停机） */
    private static final long SHUTDOWN_FLUSH_BUDGET_MILLIS = 5000;

    private final SimProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();

    private final BlockingQueue<DeviceEventMessage> queue;
    private final ExecutorService sender;

    /** producer 懒构建（volatile：发送线程持写，关闭线程读） */
    private volatile Producer producer;

    /** 连续失败计数（仅发送线程读写）：达阈值回收 producer，触发重建 */
    private int consecutiveFailures;

    /** 运行标志（关闭后拒收新事件；在途重试退出，剩余队列尽力补发） */
    private volatile boolean running = true;

    @org.springframework.beans.factory.annotation.Autowired
    public MqEventReporter(SimProperties properties) {
        this(properties, null);
    }

    /** 测试接缝：注入 fake producer（不连 broker）断言信封与保序重试 */
    MqEventReporter(SimProperties properties, Producer producer) {
        this.properties = properties;
        this.producer = producer;
        this.queue = new ArrayBlockingQueue<>(properties.getMq().getBufferSize());
        this.sender = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "swap-sim-mq-sender");
            t.setDaemon(true);
            return t;
        });
        this.sender.submit(this::drainLoop);
    }

    @Override
    public void report(DeviceEventMessage message) {
        if (!running) {
            log.warn("[MQ上报丢弃] 关闭中 cabinetNo={} eventSeq={}（dual 期 HTTP 通道并行在投）",
                    message.cabinetNo(), message.eventSeq());
            return;
        }
        while (!queue.offer(message)) {
            DeviceEventMessage dropped = queue.poll();
            if (dropped == null) {
                break;
            }
            log.error("[MQ缓冲溢出-丢弃最旧] cabinetNo={} eventSeq={}（容量={}，QUERY_STATE 兜底）",
                    dropped.cabinetNo(), dropped.eventSeq(), properties.getMq().getBufferSize());
        }
    }

    private void drainLoop() {
        while (running) {
            DeviceEventMessage message;
            try {
                message = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            MDC.put(TraceIds.MDC_KEY, message.traceId());
            try {
                sendWithRetry(message);
            } finally {
                MDC.remove(TraceIds.MDC_KEY);
            }
        }
    }

    /** 单条发送：快速退避 → 队首持留等恢复（不因 broker 故障丢弃，只可能因队列满丢弃） */
    private void sendWithRetry(DeviceEventMessage message) {
        Message mqMessage = buildMessage(message);
        if (mqMessage == null) {
            return; // 密钥缺失/构造失败=毒事件，重试无意义
        }
        int attempt = 0;
        while (running) {
            try {
                sendWithTimeout(mqMessage);
                log.info("[MQ上报成功] cabinetNo={} eventType={} eventSeq={} commandSeq={}",
                        message.cabinetNo(), message.eventType(), message.eventSeq(), message.commandSeq());
                return;
            } catch (Exception e) {
                attempt++;
                if (attempt <= MAX_FAST_RETRY) {
                    log.warn("[MQ上报失败-将第{}次重试] cabinetNo={} eventSeq={} cause={}",
                            attempt, message.cabinetNo(), message.eventSeq(), e.getMessage());
                } else if (attempt == MAX_FAST_RETRY + 1) {
                    log.error("[MQ上报转入待恢复] broker 疑似不可用，事件持留队首按 5s 节拍等待恢复 cabinetNo={} eventSeq={}",
                            message.cabinetNo(), message.eventSeq());
                }
                backoff(attempt);
            }
        }
        log.warn("[MQ上报丢弃] 关闭窗口放弃在途事件 cabinetNo={} eventSeq={}", message.cabinetNo(), message.eventSeq());
    }

    /** 信封构造（契约 §7：body 与 HTTP 同构；签名 canonical 与 HTTP 同一拼法） */
    private Message buildMessage(DeviceEventMessage event) {
        String secret = properties.secretOf(event.cabinetNo());
        if (secret == null || secret.isBlank()) {
            log.error("[MQ上报取消] 柜密钥缺失 cabinetNo={}", event.cabinetNo());
            return null;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("cabinetNo", event.cabinetNo());
        body.put("eventType", event.eventType().name());
        body.put("cellNo", event.cellNo());
        body.put("batteryNo", event.batteryNo());
        body.put("soc", event.soc());
        body.put("commandSeq", event.commandSeq());
        body.put("bootId", event.bootId());
        body.put("eventSeq", event.eventSeq());
        try {
            return provider.newMessageBuilder()
                    .setTopic(properties.getMq().getTopic())
                    .setBody(objectMapper.writeValueAsBytes(body))
                    .addProperty("X-Device-No", event.cabinetNo())
                    .addProperty("X-Device-Sign", DeviceSignature.sign(secret, DeviceSignature.canonicalEvent(
                            event.cabinetNo(), event.eventType(), event.cellNo(), event.batteryNo(),
                            event.bootId(), event.eventSeq())))
                    .addProperty(TraceIds.MQ_PROPERTY, event.traceId())
                    .setKeys(event.cabinetNo() + "-" + event.eventSeq())
                    .build();
        } catch (Exception e) {
            log.error("[MQ上报取消] 信封构造失败 cabinetNo={} eventSeq={}", event.cabinetNo(), event.eventSeq(), e);
            return null;
        }
    }

    /**
     * 单条发送（带超时）：send() 阻塞无上限，broker 挂起时会卡住队首（实测恢复慢）；
     * 标准形态 = sendAsync + get(timeout)：超时即回收 producer 触发重建，
     * 事件保持队首等待重试（保序不破）。
     */
    private void sendWithTimeout(Message mqMessage) throws Exception {
        Producer p = ensureProducer();
        CompletableFuture<SendReceipt> future = p.sendAsync(mqMessage);
        try {
            future.get(properties.getMq().getSendTimeoutMillis(), TimeUnit.MILLISECONDS);
            consecutiveFailures = 0;
        } catch (TimeoutException e) {
            future.cancel(true);
            recycleProducer("发送超时 " + properties.getMq().getSendTimeoutMillis() + "ms");
            throw new IOException("MQ send timeout", e);
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures >= 3) {
                recycleProducer("连续失败 " + consecutiveFailures + " 次");
            }
            throw e;
        }
    }

    /** 回收 producer（关闭+置空，下次发送重建）；失败方等待队列不丢 */
    private void recycleProducer(String cause) {
        synchronized (this) {
            Producer p = producer;
            producer = null;
            consecutiveFailures = 0;
            if (p != null) {
                try {
                    p.close();
                } catch (Exception e) {
                    log.debug("[MQ] 旧 producer 关闭异常（忽略）: {}", e.getMessage());
                }
            }
        }
        log.warn("[MQ] producer 已回收（{}），下次发送重建", cause);
    }

    /** producer 懒重建：broker 未就绪延迟不炸应用；构建失败抛给重试循环兜底（protected：测试接缝） */
    protected Producer createProducer() throws Exception {
        ClientConfiguration config = ClientConfiguration.newBuilder()
                .setEndpoints(properties.getMq().getEndpoint())
                .build();
        Producer p = provider.newProducerBuilder()
                .setClientConfiguration(config)
                .setTopics(properties.getMq().getTopic())
                .build();
        log.info("[MQ] producer 已就绪 endpoint={} topic={}",
                properties.getMq().getEndpoint(), properties.getMq().getTopic());
        return p;
    }

    private Producer ensureProducer() throws Exception {
        Producer p = producer;
        if (p == null) {
            synchronized (this) {
                if (producer == null) {
                    producer = createProducer();
                }
                p = producer;
            }
        }
        return p;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(1000L * Math.min(attempt, MAX_FAST_RETRY));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 优雅关闭：停收 → 剩余队列时间预算内尽力补发一次 → 关闭 producer */
    @PreDestroy
    public void shutdown() {
        running = false;
        sender.shutdownNow();
        long deadline = System.currentTimeMillis() + SHUTDOWN_FLUSH_BUDGET_MILLIS;
        int flushed = 0;
        int dropped = 0;
        DeviceEventMessage event;
        while ((event = queue.poll()) != null) {
            if (System.currentTimeMillis() > deadline) {
                dropped++;
                continue;
            }
            try {
                Message mqMessage = buildMessage(event);
                if (mqMessage != null) {
                    ensureProducer().send(mqMessage);
                    flushed++;
                } else {
                    dropped++;
                }
            } catch (Exception e) {
                dropped++;
                log.error("[MQ关闭补发失败] cabinetNo={} eventSeq={} cause={}",
                        event.cabinetNo(), event.eventSeq(), e.getMessage());
            }
        }
        if (flushed > 0 || dropped > 0) {
            log.info("[MQ关闭] 剩余队列补发={} 丢弃={}（丢弃部分由 QUERY_STATE/对账兜底）", flushed, dropped);
        }
        Producer p = producer;
        if (p != null) {
            try {
                p.close();
            } catch (Exception e) {
                log.warn("[MQ关闭] producer 关闭异常: {}", e.getMessage());
            }
        }
    }
}
