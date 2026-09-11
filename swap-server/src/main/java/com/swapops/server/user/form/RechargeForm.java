package com.swapops.server.user.form;

import lombok.Data;

/**
 * 充值请求（S3.4）。
 */
@Data
public class RechargeForm {

    /** 充值金额（分） */
    private Integer amountFen;
}
