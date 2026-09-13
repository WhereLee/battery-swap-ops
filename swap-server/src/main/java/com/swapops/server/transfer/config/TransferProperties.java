package com.swapops.server.transfer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 调拨规则参数（swap.transfer.*，S4.2 启发式规则）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.transfer")
public class TransferProperties {

    /** 富余站保留满电数（低于此不外调） */
    private int surplusKeepLevel = 4;

    /** 缺口站目标满电数（低于此视为缺口） */
    private int deficitTargetLevel = 3;

    /** 单任务最大调拨量（防一次搬空） */
    private int maxPerTask = 3;
}
