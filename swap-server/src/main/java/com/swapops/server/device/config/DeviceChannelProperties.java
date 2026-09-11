package com.swapops.server.device.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 设备通道配置（swap.device.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.device")
public class DeviceChannelProperties {

    /** 模拟器（柜）基地址——接真实设备时即协议网关地址 */
    private String simBaseUrl;

    /** 心跳超时秒数（Redis 在线 key TTL） */
    private int heartbeatTimeoutSeconds = 60;

    /** 满电阈值（soc ≥ 阈值转 FULL） */
    private int socFullThreshold = 90;

    /** 下行连接超时毫秒 */
    private int connectTimeoutMillis = 2000;

    /** 下行读超时毫秒 */
    private int readTimeoutMillis = 3000;
}
