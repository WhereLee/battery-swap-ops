package com.swapops.server.order.form;

import lombok.Data;

/**
 * 支付网关回调报文（S3.4，mock 网关同构）。
 */
@Data
public class PayCallbackForm {

    /** 平台交易号 */
    private String tradeNo;

    /** SUCCESS / FAIL（其他值按失败关闭） */
    private String result;

    /** HMAC-SHA256(tradeNo|result) 小写 hex */
    private String sign;
}
