package com.swapops.sim.reporter;

import com.swapops.contract.EventType;
import com.swapops.sim.config.SimProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件通道路由单测（S3.5）：三态路由 + 配置错位 fail-fast。
 */
@DisplayName("事件通道路由（http/mq/dual）")
@ExtendWith(MockitoExtension.class)
class ChannelRouterEventReporterTest {

    @Mock
    private HttpEventReporter httpReporter;
    @Mock
    private MqEventReporter mqReporter;
    @Mock
    private ObjectProvider<MqEventReporter> provider;

    private SimProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SimProperties();
    }

    private DeviceEventMessage message() {
        return new DeviceEventMessage("SWAP-C-001", EventType.DOOR_OPENED, 3, null, null,
                7L, "boot-1", 42L, "trace-1");
    }

    @Test
    @DisplayName("非法取值：构造期 fail-fast")
    void 非法取值failFast() {
        properties.setEventChannel("kafka");

        assertThatThrownBy(() -> new ChannelRouterEventReporter(properties, httpReporter, provider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法取值");
    }

    @Test
    @DisplayName("mq 形态但 MQ 通道关闭：构造期 fail-fast")
    void mq形态通道关闭failFast() {
        properties.setEventChannel("mq");
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> new ChannelRouterEventReporter(properties, httpReporter, provider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("配置错位");
    }

    @Test
    @DisplayName("http：仅 HTTP 上报")
    void httpOnly() {
        properties.setEventChannel("http");
        ChannelRouterEventReporter router = new ChannelRouterEventReporter(properties, httpReporter, provider);

        router.report(message());

        verify(httpReporter).report(message());
        verify(mqReporter, never()).report(message());
    }

    @Test
    @DisplayName("mq：仅 MQ 上报")
    void mqOnly() {
        properties.setEventChannel("mq");
        when(provider.getIfAvailable()).thenReturn(mqReporter);
        when(provider.getObject()).thenReturn(mqReporter);
        ChannelRouterEventReporter router = new ChannelRouterEventReporter(properties, httpReporter, provider);

        router.report(message());

        verify(mqReporter).report(message());
        verify(httpReporter, never()).report(message());
    }

    @Test
    @DisplayName("dual：HTTP + MQ 双写（同一 traceId）")
    void dual双写() {
        properties.setEventChannel("dual");
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, java.util.function.Consumer.class).accept(mqReporter);
            return null;
        }).when(provider).ifAvailable(org.mockito.ArgumentMatchers.any());
        ChannelRouterEventReporter router = new ChannelRouterEventReporter(properties, httpReporter, provider);

        router.report(message());

        verify(httpReporter).report(message());
        verify(mqReporter).report(message());
    }
}
