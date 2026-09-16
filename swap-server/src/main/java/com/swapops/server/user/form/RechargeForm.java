package com.swapops.server.user.form;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 充值请求（S3.4）。
 */
@Data
public class RechargeForm {

    /** 充值金额（分）；上界（pay.max-recharge-fen）由服务层校验 */
    @NotNull(message = "充值金额必填")
    @Min(value = 1, message = "充值金额需大于 0")
    private Integer amountFen;
}
