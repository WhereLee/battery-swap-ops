package com.swapops.server.order.controller;

import com.swapops.server.common.Result;
import com.swapops.server.order.entity.PayOrderEntity;
import com.swapops.server.order.form.PayCallbackForm;
import com.swapops.server.order.service.pay.PayOrderService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 支付网关回调（S3.4）：公开端点，安全由 HMAC 验签保证（无用户/管理 token）。
 * 幂等：重复回调直接返回成功；失败/撤销回调关闭支付单。
 */
@RestController
@RequestMapping("pay")
public class PayCallbackController {

    private final PayOrderService payOrderService;

    public PayCallbackController(PayOrderService payOrderService) {
        this.payOrderService = payOrderService;
    }

    @PostMapping("/callback")
    public Result<Map<String, Object>> callback(@RequestBody PayCallbackForm form) {
        PayOrderEntity order = payOrderService.handleCallback(form.getTradeNo(), form.getResult(), form.getSign());
        return Result.ok(payOrderService.view(order));
    }
}
