package com.swapops.server.alarm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 告警治理参数（swap.alarm.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.alarm")
public class AlarmProperties {

    /** 同 (type, deviceNo) 告警去重窗口秒（窗口内重复只日志） */
    private int dedupWindowSeconds = 300;

    /** 单类型每分钟入库上限（超限只日志，防告警风暴） */
    private int rateLimitPerMinute = 30;

    /** 批量离线合并阈值（>= 阈值合并为一条 BATCH_OFFLINE） */
    private int batchOfflineThreshold = 5;

    /** 离线扫描间隔毫秒 */
    private long offlineScanIntervalMs = 30000;

    /** 看护扫描间隔毫秒 */
    private long watchdogIntervalMs = 60000;

    /** Agent 告警事件发布开关（topic swap-alarm） */
    private boolean eventEnabled = true;
}
