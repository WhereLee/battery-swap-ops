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
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警事件发布器（S3.6，Agent 接缝①）：topic swap-alarm，信封
 * {alarmId,alarmType,deviceType,deviceNo,content,handled,handler,createTime,traceId}。
 * 本地告警落库是事实源；发布失败只告警不重试（Agent 离线不阻塞告警生成），producer 懒构建。
 */
@Slf4j
@Component
public class AlarmEventPublisher {

    private final DeviceChannelProperties deviceProperties;
    private final AlarmProperties alarmProperties;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Producer producer;

    public AlarmEventPublisher(DeviceChannelProperties deviceProperties, AlarmProperties alarmProperties) {
        this.deviceProperties = deviceProperties;
        this.alarmProperties = alarmProperties;
    }

    /** 发布告警创建事件 */
    public void publish(AlarmEntity alarm) {
        publish(alarm, alarm.getHandled() != null && alarm.getHandled() == 1 ? "HANDLED" : "RAISED");
    }

    /** 发布人工处理事件（审计） */
    public void publishHandled(AlarmEntity alarm, Long handler) {
        publish(alarm, "HANDLED");
    }

    private void publish(AlarmEntity alarm, String eventKind) {
        if (!alarmProperties.isEventEnabled()) {
            return;
        }
        try {
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
            String traceId = TraceIdFilter.currentOrGenerate();
            envelope.put("traceId", traceId);
            Message message = provider.newMessageBuilder()
                    .setTopic("swap-alarm")
                    .setBody(objectMapper.writeValueAsBytes(envelope))
                    .addProperty(TraceIdFilter.MDC_KEY, traceId)
                    .setKeys("alarm-" + alarm.getId())
                    .build();
            ensureProducer().send(message);
            log.info("[告警事件已发布] kind={} alarmId={} type={} deviceNo={}",
                    eventKind, alarm.getId(), alarm.getAlarmType(), alarm.getDeviceNo());
        } catch (Exception e) {
            log.warn("[告警事件发布失败] 本地告警已落库，不重试 alarmId={} cause={}",
                    alarm.getId(), e.getMessage());
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
