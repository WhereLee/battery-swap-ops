package com.swapops.server.device.mq;

import com.swapops.server.device.config.DeviceChannelAuthenticator;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.service.DeviceEventService;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MQ 消费分级单测（S3.5）：正常 ack、四类毒消息 ack 丢弃、瞬时异常等重投。
 */
@DisplayName("设备事件 MQ 消费（分级）")
@ExtendWith(MockitoExtension.class)
class DeviceEventMqConsumerTest {

    @Mock
    private DeviceChannelProperties properties;
    @Mock
    private DeviceChannelAuthenticator authenticator;
    @Mock
    private DeviceEventService deviceEventService;
    @Mock
    private MessageView messageView;

    private DeviceEventMqConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new DeviceEventMqConsumer(properties, authenticator, deviceEventService);
    }

    private void givenMessage(String json, String propCabinetNo, String sign) {
        when(messageView.getProperties()).thenReturn(Map.of(
                "X-Device-No", propCabinetNo == null ? "" : propCabinetNo,
                "X-Device-Sign", sign == null ? "" : sign));
        when(messageView.getBody()).thenReturn(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
    }

    private String validJson() {
        return "{\"cabinetNo\":\"SWAP-C-001\",\"eventType\":\"DOOR_OPENED\",\"cellNo\":3,"
                + "\"bootId\":\"boot-1\",\"eventSeq\":42,\"commandSeq\":7}";
    }

    @Test
    @DisplayName("正常消息：验签 + 编排后 ACK")
    void 正常消息ACK() {
        givenMessage(validJson(), "SWAP-C-001", "sign-1");
        when(authenticator.authenticateEvent(any(), eq("sign-1"))).thenReturn(new CabinetEntity());
        when(deviceEventService.handle(any())).thenReturn(true);

        assertThat(consumer.process(messageView)).isEqualTo(DeviceEventMqConsumer.Outcome.ACK);
    }

    @Test
    @DisplayName("毒消息-非 JSON：ACK 丢弃，不进重投循环")
    void 非JSON毒消息() {
        givenMessage("not-json", "SWAP-C-001", "sign-1");

        assertThat(consumer.process(messageView)).isEqualTo(DeviceEventMqConsumer.Outcome.ACK);
        verifyNoInteractions(authenticator, deviceEventService);
    }

    @Test
    @DisplayName("毒消息-缺必填字段：ACK 丢弃")
    void 缺字段毒消息() {
        givenMessage("{\"cabinetNo\":\"SWAP-C-001\",\"eventType\":\"DOOR_OPENED\"}", "SWAP-C-001", "sign-1");

        assertThat(consumer.process(messageView)).isEqualTo(DeviceEventMqConsumer.Outcome.ACK);
        verifyNoInteractions(deviceEventService);
    }

    @Test
    @DisplayName("毒消息-property 柜号与报文体不一致：ACK 丢弃（信封完整性）")
    void 信封不一致毒消息() {
        givenMessage(validJson(), "SWAP-C-999", "sign-1");

        assertThat(consumer.process(messageView)).isEqualTo(DeviceEventMqConsumer.Outcome.ACK);
        verifyNoInteractions(authenticator, deviceEventService);
    }

    @Test
    @DisplayName("毒消息-验签失败：ACK 丢弃")
    void 验签失败毒消息() {
        givenMessage(validJson(), "SWAP-C-001", "bad-sign");
        when(authenticator.authenticateEvent(any(), eq("bad-sign")))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "签名无效"));

        assertThat(consumer.process(messageView)).isEqualTo(DeviceEventMqConsumer.Outcome.ACK);
        verifyNoInteractions(deviceEventService);
    }

    @Test
    @DisplayName("毒消息-业务拒绝（RRException）：ACK 丢弃")
    void 业务拒绝毒消息() {
        givenMessage(validJson(), "SWAP-C-001", "sign-1");
        when(authenticator.authenticateEvent(any(), eq("sign-1"))).thenReturn(new CabinetEntity());
        when(deviceEventService.handle(any()))
                .thenThrow(new com.swapops.server.common.RRException("未知事件类型"));

        assertThat(consumer.process(messageView)).isEqualTo(DeviceEventMqConsumer.Outcome.ACK);
    }

    @Test
    @DisplayName("瞬时异常（DB 抖动）：RETRY 等 broker 重投")
    void 瞬时异常等重投() {
        givenMessage(validJson(), "SWAP-C-001", "sign-1");
        when(authenticator.authenticateEvent(any(), eq("sign-1"))).thenReturn(new CabinetEntity());
        when(deviceEventService.handle(any())).thenThrow(new RuntimeException("db down"));

        assertThat(consumer.process(messageView)).isEqualTo(DeviceEventMqConsumer.Outcome.RETRY);
    }
}
