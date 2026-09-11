package com.swapops.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 计费与订单参数（swap.billing.*；生产形态为规则表，样例走配置）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.billing")
public class BillingProperties {

    /** 余额单次换电价（分）——无生效套餐时的扣费价 */
    private int balanceFeeFen = 300;

    /** 押金（分）——首借（TAKE）时缴纳，退租（RETURN）退还 */
    private int depositFen = 9900;

    /** 借出超时阈值小时数（超过后按超时费计） */
    private int overdueHours = 24;

    /** 超时费单价（分/小时，超过阈值后的部分计费） */
    private int overdueFeePerHourFen = 100;

    /** 预占超时秒数（下单后等待开仓/取电） */
    private int preemptTtlSeconds = 120;

    /** 开仓后取电/还电等待秒数（超时关闭订单并释放） */
    private int pickupTimeoutSeconds = 120;
}
