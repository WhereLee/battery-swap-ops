package com.swapops.server.device.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.server.common.RRException;
import com.swapops.server.common.filter.TraceIdFilter;
import com.swapops.server.device.config.DeviceChannelAuthenticator;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.form.DeviceEventForm;
import com.swapops.server.device.service.DeviceEventService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 设备事件 MQ 消费者（S3.5，契约 §7）：
 * <ul>
 *   <li>复用：验签调 {@link DeviceChannelAuthenticator#authenticateEvent}、编排调
 *       {@link DeviceEventService#handle}——消费路径不绕开、不重写既有逻辑；</li>
 *   <li>并发度=1：单消费线程逐条 receive→处理→ack；单队列 topic 全局有序（序守卫要求 eventSeq 单调到达）；</li>
 *   <li>失败分级：验签失败/未登记/协议垃圾/信封不一致 = 毒消息 **ack 丢弃**（防无限重投）；
 *       瞬时异常（DB 抖动等）**不 ack** 等 broker 重投，重投耗尽进死信；重复投递由业务幂等吸收；</li>
 *   <li>broker 韧性：consumer 懒构建 + 5s 节拍重建（broker 重启不炸平台）。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "swap.device.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeviceEventMqConsumer {

    /** 消费处理结论（ACK=签收不再重投；RETRY=不签收等 broker 重投） */
    enum Outcome {
        ACK, RETRY
    }

    /** 消息不可见时长（处理窗口；超时未 ack broker 自动重投） */
    private static final Duration INVISIBLE_DURATION = Duration.ofSeconds(15);

    /** 长轮询等待时长（空队列阻塞上限，也是停机信号最长感知延迟） */
    private static final Duration AWAIT_DURATION = Duration.ofSeconds(5);

    /** consumer 重建节拍 */
    private static final long REBUILD_INTERVAL_MILLIS = 5000;

    private final DeviceChannelProperties properties;
    private final DeviceChannelAuthenticator authenticator;
    private final DeviceEventService deviceEventService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();

    private volatile boolean running = true;
    private SimpleConsumer consumer;
    private Thread consumerThread;

    public DeviceEventMqConsumer(DeviceChannelProperties properties, DeviceChannelAuthenticator authenticator,
                                 DeviceEventService deviceEventService) {
        this.properties = properties;
        this.authenticator = authenticator;
        this.deviceEventService = deviceEventService;
    }

    @PostConstruct
    public void start() {
        consumerThread = new Thread(this::consumeLoop, "platform-mq-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        log.info("[MQ事件消费] 消费线程已启动 endpoint={} topic={} group={}（并发度=1 保序）",
                properties.getMq().getEndpoint(), properties.getMq().getTopic(), properties.getMq().getConsumerGroup());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        closeConsumerQuietly();
    }

    private void consumeLoop() {
        while (running) {
            try {
                SimpleConsumer c = ensureConsumer();
                if (c == null) {
                    sleep(REBUILD_INTERVAL_MILLIS);
                    continue;
                }
                List<MessageView> messages = c.receive(1, INVISIBLE_DURATION);
                for (MessageView message : messages) {
                    if (!running) {
                        return; // 停机窗口未 ack 消息由 broker 重投（重启窗口零丢失）
                    }
                    handleOne(c, message);
                }
            } catch (ClientException e) {
                if (running) {
                    log.error("[MQ事件消费] receive 异常，{}ms 后重建 consumer: {}", REBUILD_INTERVAL_MILLIS, e.getMessage());
                }
                closeConsumerQuietly();
                sleep(REBUILD_INTERVAL_MILLIS);
            } catch (Exception e) {
                if (running) {
                    log.error("[MQ事件消费] 消费循环意外异常（{}ms 后继续）", REBUILD_INTERVAL_MILLIS, e);
                }
                sleep(REBUILD_INTERVAL_MILLIS);
            }
        }
    }

    private void handleOne(SimpleConsumer c, MessageView message) {
        String traceId = message.getProperties().get(TraceIdFilter.MDC_KEY);
        if (traceId != null && !traceId.isEmpty()) {
            MDC.put(TraceIdFilter.MDC_KEY, traceId);
        }
        try {
            Outcome outcome;
            try {
                outcome = process(message);
            } catch (Exception e) {
                log.error("[MQ事件消费] 处理意外异常，按瞬时故障等 broker 重投 msgId={}", message.getMessageId(), e);
                outcome = Outcome.RETRY;
            }
            if (outcome == Outcome.ACK) {
                try {
                    c.ack(message);
                } catch (Exception e) {
                    log.error("[MQ事件消费] ack 失败（broker 将重投，业务幂等吸收）msgId={} cause={}",
                            message.getMessageId(), e.getMessage());
                }
            }
        } finally {
            MDC.remove(TraceIdFilter.MDC_KEY);
        }
    }

    /**
     * 分级决策（与 IO 分离，包内可见供单测注入消息对象）：
     * 解析信封 → 必填/一致性校验 → 验签（复用）→ 编排（复用）。
     */
    Outcome process(MessageView message) {
        Map<String, String> props = message.getProperties();
        String propCabinetNo = props.get("X-Device-No");
        String signature = props.get("X-Device-Sign");
        DeviceEventForm form;
        try {
            ByteBuffer body = message.getBody().duplicate();
            byte[] bytes = new byte[body.remaining()];
            body.get(bytes);
            form = objectMapper.readValue(bytes, DeviceEventForm.class);
        } catch (Exception e) {
            log.error("[MQ事件消费-毒消息丢弃] 消息体非协议事件 JSON（不重投）msgId={} cause={}",
                    message.getMessageId(), e.getMessage());
            return Outcome.ACK;
        }
        if (form == null || form.getCabinetNo() == null || form.getCabinetNo().isEmpty()
                || form.getEventType() == null || form.getEventType().isEmpty()
                || form.getBootId() == null || form.getBootId().isEmpty()
                || form.getEventSeq() == null) {
            log.error("[MQ事件消费-毒消息丢弃] 缺必填字段(cabinetNo/eventType/bootId/eventSeq)（不重投）msgId={}",
                    message.getMessageId());
            return Outcome.ACK;
        }
        if (!form.getCabinetNo().equals(propCabinetNo)) {
            log.error("[MQ事件消费-毒消息丢弃] property X-Device-No 与报文体不一致（不重投）msgId={} prop={} body={}",
                    message.getMessageId(), propCabinetNo, form.getCabinetNo());
            return Outcome.ACK;
        }
        try {
            authenticator.authenticateEvent(form, signature);
        } catch (ResponseStatusException | IllegalArgumentException e) {
            log.error("[MQ事件消费-毒消息丢弃] 验签/协议校验失败（不重投）cabinetNo={} msgId={} cause={}",
                    form.getCabinetNo(), message.getMessageId(), e.getMessage());
            return Outcome.ACK;
        }
        try {
            deviceEventService.handle(form);
        } catch (RRException e) {
            log.error("[MQ事件消费-毒消息丢弃] 业务拒绝（不重投）cabinetNo={} msgId={} msg={}",
                    form.getCabinetNo(), message.getMessageId(), e.getMessage());
            return Outcome.ACK;
        } catch (Exception e) {
            log.error("[MQ事件消费-等重投] 业务处理瞬时异常 cabinetNo={} eventSeq={} msgId={} cause={}",
                    form.getCabinetNo(), form.getEventSeq(), message.getMessageId(), e.getMessage());
            return Outcome.RETRY;
        }
        log.info("[MQ事件消费] cabinetNo={} eventSeq={} bootId={} commandSeq={} eventType={}",
                form.getCabinetNo(), form.getEventSeq(), form.getBootId(), form.getCommandSeq(), form.getEventType());
        return Outcome.ACK;
    }

    private SimpleConsumer ensureConsumer() {
        if (consumer != null) {
            return consumer;
        }
        try {
            ClientConfiguration config = ClientConfiguration.newBuilder()
                    .setEndpoints(properties.getMq().getEndpoint())
                    .build();
            consumer = provider.newSimpleConsumerBuilder()
                    .setClientConfiguration(config)
                    .setConsumerGroup(properties.getMq().getConsumerGroup())
                    .setSubscriptionExpressions(Collections.singletonMap(
                            properties.getMq().getTopic(), FilterExpression.SUB_ALL))
                    .setAwaitDuration(AWAIT_DURATION)
                    .build();
            log.info("[MQ事件消费] consumer 已就绪 endpoint={} topic={} group={}",
                    properties.getMq().getEndpoint(), properties.getMq().getTopic(),
                    properties.getMq().getConsumerGroup());
        } catch (Exception e) {
            log.error("[MQ事件消费] consumer 构建失败（broker 未就绪？），{}ms 后重试: {}",
                    REBUILD_INTERVAL_MILLIS, e.getMessage());
        }
        return consumer;
    }

    private void closeConsumerQuietly() {
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                log.warn("[MQ事件消费] consumer 关闭异常: {}", e.getMessage());
            }
            consumer = null;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
