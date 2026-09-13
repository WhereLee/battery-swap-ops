package com.swapops.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 电池健康参数（swap.battery.*，S4.1）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.battery")
public class BatteryHealthProperties {

    /** SOH 告警阈值（低于该值产生 BATTERY_HEALTH_LOW，归工单处置） */
    private int sohWarnThreshold = 80;

    /** 健康扫描间隔毫秒（联调可缩短） */
    private long scanIntervalMs = 3600_000L;

    /** SOH 分级：优/良 分界 */
    private int sohGoodThreshold = 90;

    private int sohFairThreshold = 80;
}
