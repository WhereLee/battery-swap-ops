package com.swapops.server.dev;

import com.swapops.server.common.Result;
import com.swapops.server.order.entity.PayOrderEntity;
import com.swapops.server.order.service.pay.PayOrderService;
import com.swapops.server.order.service.pay.PaySignatureService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * mock 支付网关（仅 swap.dev.enabled=true，联调用）：
 * 支付页只是两个表单按钮；notify 用共享密钥生成合法签名后走与真实回调完全相同的验签入账路径。
 */
@RestController
@RequestMapping("pay/mock")
@ConditionalOnProperty(prefix = "swap.dev", name = "enabled", havingValue = "true")
public class PayMockController {

    private final PayOrderService payOrderService;
    private final PaySignatureService paySignatureService;

    public PayMockController(PayOrderService payOrderService, PaySignatureService paySignatureService) {
        this.payOrderService = payOrderService;
        this.paySignatureService = paySignatureService;
    }

    @GetMapping(value = "/page/{tradeNo}", produces = "text/html;charset=UTF-8")
    public String page(@PathVariable String tradeNo) {
        return "<html><body><h3>Mock Pay</h3><p>tradeNo: " + tradeNo + "</p>"
                + "<form method=\"post\" action=\"/api/pay/mock/notify/" + tradeNo + "?result=SUCCESS\">"
                + "<button type=\"submit\">支付成功</button></form>"
                + "<form method=\"post\" action=\"/api/pay/mock/notify/" + tradeNo + "?result=FAIL\">"
                + "<button type=\"submit\">支付失败</button></form>"
                + "</body></html>";
    }

    @PostMapping("/notify/{tradeNo}")
    public Result<Map<String, Object>> notify(@PathVariable String tradeNo,
                                              @RequestParam(defaultValue = "SUCCESS") String result) {
        String sign = paySignatureService.sign(tradeNo, result);
        PayOrderEntity handled = payOrderService.handleCallback(tradeNo, result, sign);
        return Result.ok(payOrderService.view(handled));
    }
}
