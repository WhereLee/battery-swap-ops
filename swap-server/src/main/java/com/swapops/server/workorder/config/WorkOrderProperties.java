package com.swapops.server.workorder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工单参数（swap.workorder.*）：SLA 时长按严重级配置（联调可缩短）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.workorder")
public class WorkOrderProperties {

    private int slaMinutesHigh = 30;

    private int slaMinutesMedium = 120;

    private int slaMinutesLow = 480;

    public int slaMinutes(String severity) {
        return switch (severity == null ? "MEDIUM" : severity.toUpperCase()) {
            case "HIGH" -> slaMinutesHigh;
            case "LOW" -> slaMinutesLow;
            default -> slaMinutesMedium;
        };
    }
}
