package com.swapops.sim.reporter;

import com.swapops.contract.EventType;
import com.swapops.sim.config.SimProperties;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * sim MQ 上报单测（S3.5）：信封（body 同构 HTTP + property 签名/柜号/traceId + keys）、发送成功、关闭拒收。
 */
@DisplayName("sim MQ 事件上报（信封与保序队列）")
@ExtendWith(MockitoExtension.class)
class MqEventReporterTest {

    @Mock
    private Producer producer;

    private SimProperties properties;
    private MqEventReporter reporter;

    private SimProperties properties(String secret) {
        SimProperties props = new SimProperties();
        props.setServerBaseUrl("http://127.0.0.1:8400/api");
        SimProperties.CabinetCfg cfg = new SimProperties.CabinetCfg();
        cfg.setCabinetNo("SWAP-C-001");
        cfg.setSecret(secret);
        props.setCabinets(List.of(cfg));
        return props;
    }

    private DeviceEventMessage message() {
        return new DeviceEventMessage("SWAP-C-001", EventType.BATTERY_OUT, 3, "BAT-0001", 88,
                7L, "boot-1", 42L, "trace-1");
    }

    @AfterEach
    void tearDown() {
        if (reporter != null) {
            reporter.shutdown();
        }
    }

    @Test
    @DisplayName("发送：body 与 HTTP 同构；签名/柜号/traceId 进 property；keys=柜-事件序")
    void 正常发送信封() throws Exception {
        properties = properties("0123456789abcdef0123456789abcdef");
        reporter = new MqEventReporter(properties, producer);

        reporter.report(message());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(producer).send(captor.capture()));
        Message sent = captor.getValue();
        Map<String, String> props = sent.getProperties();
        assertThat(props).containsKeys("X-Device-No", "X-Device-Sign", "traceId")
                .containsEntry("X-Device-No", "SWAP-C-001")
                .containsEntry("traceId", "trace-1");
        assertThat(props.get("X-Device-Sign")).matches("[0-9a-f]{64}");
        assertThat(sent.getKeys()).containsExactly("SWAP-C-001-42");
        String body = decode(sent.getBody());
        assertThat(body).contains("\"cabinetNo\":\"SWAP-C-001\"")
                .contains("BATTERY_OUT")
                .contains("\"eventSeq\":42");
    }

    @Test
    @DisplayName("密钥缺失：毒事件不发送（重试无意义）")
    void 密钥缺失不发送() throws Exception {
        properties = properties("");
        reporter = new MqEventReporter(properties, producer);

        reporter.report(message());

        Thread.sleep(300);
        verify(producer, never()).send(any(Message.class));
    }

    @Test
    @DisplayName("关闭后拒收新事件")
    void 关闭拒收() throws Exception {
        properties = properties("0123456789abcdef0123456789abcdef");
        reporter = new MqEventReporter(properties, producer);
        reporter.shutdown();

        reporter.report(message());

        verify(producer, never()).send(any(Message.class));
    }

    private String decode(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
