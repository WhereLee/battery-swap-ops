package com.swapops.server.dev;

import com.swapops.server.common.RRException;
import com.swapops.server.common.Result;
import com.swapops.server.common.ratelimit.ClientIpResolver;
import com.swapops.server.order.entity.PayOrderEntity;
import com.swapops.server.order.service.pay.PayOrderService;
import com.swapops.server.order.service.pay.PaySignatureService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * mock 支付网关（仅 swap.dev.enabled=true，联调用）：
 * 支付页只是两个表单按钮；notify 用共享密钥生成合法签名后走与真实回调完全相同的验签入账路径。
 *
 * <p><b>批次36 加固（AUD-7）</b>——两个问题都不影响生产（{@code swap.dev.enabled} 默认 false），
 * 但它们是"dev 开关被误开/误部署"时最危险的两个面，故按生产标准收口：
 * <ol>
 *   <li><b>反射型 XSS</b>：{@code page()} 原来把路径变量原样拼进 HTML。现在先按单号字符集白名单校验，
 *       再做 HTML 转义——两道都要，校验挡畸形输入、转义挡"合法字符集内的恶意内容"（如引号）。</li>
 *   <li><b>无鉴权造币入口</b>：{@code notify()} 用服务端密钥自签即入账，任何能访问到服务的人只要知道
 *       tradeNo 就能把充值单"付成功"。现在只接受<b>回环地址</b>的调用（联调的浏览器/脚本/模拟器都在同机），
 *       并且客户端地址走 {@link ClientIpResolver}——它是受信代理感知的，因此"经 nginx 反代进来的远程
 *       请求"不会被误判为本机（这正是批次33 建立的同一条边界）。</li>
 * </ol>
 * 每次 mock 入账都打一条 WARN 日志，便于事后分辨"这笔钱是真实渠道还是 mock 点的"。
 */
@Slf4j
@RestController
@RequestMapping("pay/mock")
@ConditionalOnProperty(prefix = "swap.dev", name = "enabled", havingValue = "true")
public class PayMockController {

    /** 支付单号字符集（与 PayOrderService 生成规则一致：字母数字与连字符） */
    private static final Pattern TRADE_NO_PATTERN = Pattern.compile("^[A-Za-z0-9-]{4,64}$");

    private static final Pattern RESULT_PATTERN = Pattern.compile("^(SUCCESS|FAIL)$");

    private final PayOrderService payOrderService;
    private final PaySignatureService paySignatureService;
    private final com.swapops.server.payrecon.service.ChannelReconService channelReconService;
    private final ClientIpResolver clientIpResolver;

    public PayMockController(PayOrderService payOrderService, PaySignatureService paySignatureService,
                             com.swapops.server.payrecon.service.ChannelReconService channelReconService,
                             ClientIpResolver clientIpResolver) {
        this.payOrderService = payOrderService;
        this.paySignatureService = paySignatureService;
        this.channelReconService = channelReconService;
        this.clientIpResolver = clientIpResolver;
    }

    /** S7 WP-C：渠道账单导出（mock T+1 账单；anomaly 注入差异供剧本/演示） */
    @GetMapping(value = "/bill/export", produces = "text/csv;charset=UTF-8")
    public String exportBill(@RequestParam String date,
                             @RequestParam(required = false) String anomaly) {
        return channelReconService.exportBillCsv(date, anomaly);
    }

    @GetMapping(value = "/page/{tradeNo}", produces = "text/html;charset=UTF-8")
    public String page(@PathVariable String tradeNo) {
        requireTradeNo(tradeNo);
        // 只拼接"通过字符集白名单"的单号：白名单里没有需要 HTML 转义的字符。
        // 若将来放宽 {@link #TRADE_NO_PATTERN}（例如允许点号/斜杠），必须同时引入 HTML 转义。
        return "<html><head><meta charset=\"UTF-8\"><title>Mock Pay</title></head><body>"
                + "<h3>Mock Pay</h3><p>tradeNo: " + tradeNo + "</p>"
                + "<form method=\"post\" action=\"/api/pay/mock/notify/" + tradeNo + "?result=SUCCESS\">"
                + "<button type=\"submit\">支付成功</button></form>"
                + "<form method=\"post\" action=\"/api/pay/mock/notify/" + tradeNo + "?result=FAIL\">"
                + "<button type=\"submit\">支付失败</button></form>"
                + "</body></html>";
    }

    @PostMapping("/notify/{tradeNo}")
    public Result<Map<String, Object>> notify(@PathVariable String tradeNo,
                                              @RequestParam(defaultValue = "SUCCESS") String result,
                                              HttpServletRequest request) {
        requireLoopback(request);
        requireTradeNo(tradeNo);
        if (!RESULT_PATTERN.matcher(result).matches()) {
            throw new RRException("mock 支付结果只能是 SUCCESS/FAIL: " + result);
        }
        String sign = paySignatureService.sign(tradeNo, result);
        PayOrderEntity handled = payOrderService.handleCallback(tradeNo, result, sign);
        log.warn("[mock-pay] 由 mock 网关入账（非真实渠道） tradeNo={} result={} client={}",
                tradeNo, result, clientIpResolver.resolve(request));
        return Result.ok(payOrderService.view(handled));
    }

    /** 单号白名单：畸形输入在进业务前就被拒，不参与任何拼接。 */
    private void requireTradeNo(String tradeNo) {
        if (tradeNo == null || !TRADE_NO_PATTERN.matcher(tradeNo).matches()) {
            throw new RRException("支付单号格式非法: " + (tradeNo == null ? "null" : tradeNo));
        }
    }

    /**
     * mock 入账只对回环开放：这是"自签即入账"的入口，一旦能远程调用就等于无成本造币。
     * 地址用受信代理感知的解析器，避免"经反代的远程请求看起来来自 127.0.0.1"。
     */
    private void requireLoopback(HttpServletRequest request) {
        String client = clientIpResolver.resolve(request);
        if (!isLoopback(client)) {
            log.warn("[mock-pay] 拒绝非本机调用 client={}", client);
            throw new RRException("mock 支付网关仅限本机调用: " + client);
        }
    }

    private boolean isLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)
                || "localhost".equals(ip);
    }
}
