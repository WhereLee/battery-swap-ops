package com.swapops.sim.reporter;

import com.swapops.sim.config.SimProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 事件通道路由（S3.5）：按 swap.sim.event-channel 三态路由——
 * dual=HTTP+MQ 双写（对照期，同一 traceId 两路径可同号对照）/ mq=仅 MQ（终态）/ http=仅 HTTP（降级回滚）。
 *
 * <p>fail-fast：取值非法、或 mq 形态但 MQ 通道关闭——构造期即抛错（与密钥缺失同哲学，不静默降级）。
 * 多通道独立成败：各通道故障/重试在各自发送线程消化，动作线程零感知。心跳恒 HTTP（判活不依赖 broker）。</p>
 */
@Primary
@Component
public class ChannelRouterEventReporter implements EventReporter {

    private final SimProperties properties;
    private final HttpEventReporter httpReporter;
    private final ObjectProvider<MqEventReporter> mqReporter;

    public ChannelRouterEventReporter(SimProperties properties, HttpEventReporter httpReporter,
                                      ObjectProvider<MqEventReporter> mqReporter) {
        this.properties = properties;
        this.httpReporter = httpReporter;
        this.mqReporter = mqReporter;
        String channel = properties.getEventChannel();
        if (!"mq".equals(channel) && !"http".equals(channel) && !"dual".equals(channel)) {
            throw new IllegalStateException("swap.sim.event-channel 非法取值：" + channel + "（可选 mq/http/dual）");
        }
        if ("mq".equals(channel) && mqReporter.getIfAvailable() == null) {
            throw new IllegalStateException("swap.sim.event-channel=mq 但 swap.sim.mq.enabled=false——事件通道配置错位");
        }
    }

    @Override
    public void report(DeviceEventMessage message) {
        String channel = properties.getEventChannel();
        if ("mq".equals(channel)) {
            mqReporter.getObject().report(message);
            return;
        }
        if ("http".equals(channel)) {
            httpReporter.report(message);
            return;
        }
        // dual：两通道共用同一 traceId，平台侧按 traceId 对照两路径处理痕迹
        httpReporter.report(message);
        mqReporter.ifAvailable(mq -> mq.report(message));
    }
}
