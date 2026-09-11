package com.swapops.server.order.enums;

/**
 * 退款单状态（S3.4）：WAIT 待执行（含失败待重试）；SUCCESS 已入账。
 */
public enum RefundStatus {
    WAIT,
    SUCCESS
}
