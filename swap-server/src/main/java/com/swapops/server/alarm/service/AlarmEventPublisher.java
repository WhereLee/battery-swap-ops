package com.swapops.server.alarm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.server.alarm.config.AlarmProperties;
import com.swapops.server.alarm.entity.AlarmEntity;
import com.swapops.server.common.filter.TraceIdFilter;
import com.swapops.server.device.config.DeviceChannelProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 告警事件发布器（S3.6，Agent 接缝①）：topic swap-alarm，信封
 * {alarmId,alarmType,deviceType,deviceNo,content,handled,handler,createTime,traceId}。
 * 本地告警落库是事实源；发布失败只告警不重试（Agent 离线不阻塞告警生成），producer 懒构建。
 */
@Slf4j
@Component
public class AlarmEventPublisher {

    /** 发布超时（send 无上限会卡住 outbox 中继线程） */
    private static final long SEND_TIMEOUT_MILLIS = 3000;

    private final DeviceChannelProperties deviceProperties;
    private final AlarmProperties alarmProperties;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Producer producer;

    public AlarmEventPublisher(DeviceChannelProperties deviceProperties, AlarmProperties alarmProperties) {
        this.deviceProperties = deviceProperties;
        this.alarmProperties = alarmProperties;
    }

    /** 构建告警事件信封（S3.8 WP6：由业务事务内落 outbox，中继补投时不再构建） */
    public String buildEnvelope(AlarmEntity alarm, String eventKind) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("alarmId", alarm.getId());
        envelope.put("alarmType", alarm.getAlarmType());
        envelope.put("deviceType", alarm.getDeviceType());
        envelope.put("deviceNo", alarm.getDeviceNo());
        envelope.put("content", alarm.getContent());
        envelope.put("handled", alarm.getHandled());
        envelope.put("handler", alarm.getHandler());
        envelope.put("createTime", alarm.getCreateTime());
        envelope.put("handledTime", alarm.getHandledTime());
        envelope.put("eventKind", eventKind);
        envelope.put("traceId", TraceIdFilter.currentOrGenerate());
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("告警信封构建失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发布已构建的信封（outbox 中继调用）。
     * 失败**抛出**（中继据此退避重试/死信），不再吞异常——可靠性从"日志告警"升级为"持久补投"。
     */
    public void publishEnvelope(String envelopeJson, String traceId, String keys) {
        if (!alarmProperties.isEventEnabled()) {
            return;
        }
        try {
            Message message = provider.newMessageBuilder()
                    .setTopic("swap-alarm")
                    .setBody(envelopeJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .addProperty(TraceIdFilter.MDC_KEY,
                            traceId == null ? TraceIdFilter.currentOrGenerate() : traceId)
                    .setKeys(keys)
                    .build();
            CompletableFuture<SendReceipt> future = ensureProducer().sendAsync(message);
            try {
                future.get(SEND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IllegalStateException("告警事件发布超时(" + SEND_TIMEOUT_MILLIS + "ms)");
            }
            log.info("[outbox] 告警事件已发布 keys={}", keys);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("告警事件发布失败: " + e.getMessage(), e);
        }
    }

    private Producer ensureProducer() throws Exception {
        Producer p = producer;
        if (p == null) {
            synchronized (this) {
                if (producer == null) {
                    ClientConfiguration config = ClientConfiguration.newBuilder()
                            .setEndpoints(deviceProperties.getMq().getEndpoint())
                            .build();
                    producer = provider.newProducerBuilder()
                            .setClientConfiguration(config)
                            .setTopics("swap-alarm")
                            .build();
                }
                p = producer;
            }
        }
        return p;
    }
}
