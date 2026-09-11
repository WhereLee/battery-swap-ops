package com.swapops.server.order.enums;

/**
 * 充值支付单状态（S3.4）：WAIT 待回调 → SUCCESS 已入账 / CLOSED 已关闭（失败或撤销）。
 */
public enum PayOrderStatus {
    WAIT,
    SUCCESS,
    CLOSED
}
