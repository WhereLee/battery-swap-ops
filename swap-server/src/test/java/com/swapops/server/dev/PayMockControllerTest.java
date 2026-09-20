package com.swapops.server.dev;

import com.swapops.server.common.RRException;
import com.swapops.server.order.entity.PayOrderEntity;
import com.swapops.server.order.service.pay.PayOrderService;
import com.swapops.server.order.service.pay.PaySignatureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * mock 支付网关加固测试（批次36 / AUD-7）。
 *
 * <p>两个面：① 支付页的路径变量不再原样拼 HTML（畸形单号直接拒，白名单内无待转义字符）；
 * ② "自签即入账"的 notify 只对**回环地址**开放，且地址判定走受信代理感知的解析器
 * （否则经 nginx 反代的远程请求会被误判为本机——正是批次33 建立的那条边界）。
 */
@DisplayName("mock 支付网关（dev-only 加固）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayMockControllerTest {

    @Mock
    private PayOrderService payOrderService;
    @Mock
    private PaySignatureService paySignatureService;
    @Mock
    private com.swapops.server.payrecon.service.ChannelReconService channelReconService;

    private PayMockController controller;

    @BeforeEach
    void setUp() {
        controller = new PayMockController(payOrderService, paySignatureService, channelReconService,
                new DevLoopbackGuard());
    }

    private MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/pay/mock/notify/T1");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private void givenCallbackHandled() {
        PayOrderEntity entity = new PayOrderEntity();
        entity.setTradeNo("PAY1");
        // The sign is generated server-side; stub it so the callback stub can match a real
        // value (Mockito's anyString() does NOT match null, which silently produced an
        // unstubbed call and an empty view map on the first attempt at this test).
        when(paySignatureService.sign(anyString(), anyString())).thenReturn("SIG-TEST");
        when(payOrderService.handleCallback(anyString(), anyString(), anyString())).thenReturn(entity);
        when(payOrderService.view(org.mockito.ArgumentMatchers.any(PayOrderEntity.class)))
                .thenReturn(Map.of("tradeNo", "PAY1"));
    }

    @Test
    @DisplayName("支付页：合法单号正常渲染（白名单内不含待转义字符）")
    void 支付页合法单号() {
        String html = controller.page("PAY-1234567890");

        assertThat(html).contains("PAY-1234567890");
        assertThat(html).contains("/api/pay/mock/notify/PAY-1234567890?result=SUCCESS");
    }

    @Test
    @DisplayName("支付页：畸形单号（脚本/引号/路径穿越）一律 400，不参与任何拼接")
    void 支付页拒绝畸形单号() {
        for (String bad : new String[]{"<script>alert(1)</script>", "a\"onmouseover=x", "../../etc/passwd",
                "PAY 1", "", "ab"}) {
            assertThatThrownBy(() -> controller.page(bad))
                    .as("畸形单号必须被拒: %s", bad)
                    .isInstanceOf(RRException.class);
        }
    }

    @Test
    @DisplayName("notify：本机调用放行，签名由服务端生成后走同一条验签入账路径")
    void 本机调用放行() {
        givenCallbackHandled();

        Map<String, Object> view = controller.notify("PAY-1", "SUCCESS", request("127.0.0.1", null)).getData();

        assertThat(view).containsEntry("tradeNo", "PAY1");
        verify(paySignatureService).sign("PAY-1", "SUCCESS");
        // 服务端自签的签名就是送进回调校验的那个值（mock 与真实回调共用同一条入账路径）
        verify(payOrderService).handleCallback("PAY-1", "SUCCESS", "SIG-TEST");
    }

    @Test
    @DisplayName("notify：非本机调用拒绝（等于关掉无成本造币入口）")
    void 远程调用拒绝() {
        givenCallbackHandled();

        assertThatThrownBy(() -> controller.notify("PAY-1", "SUCCESS", request("203.0.113.7", null)))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("仅限本机");
        verify(payOrderService, never()).handleCallback(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("notify：反代时按真实客户端判定，且**不依赖限流配置**（缺省 trusted-proxies 也必须拒远程）")
    void 反代后的远程调用拒绝() {
        givenCallbackHandled();

        // 云上 nginx 形态：直连地址是 nginx（127.0.0.1），XFF 最右段是真实客户端。
        // 批次43 补（F-15）：这里刻意使用**缺省**配置——旧实现把回环判定寄生在限流解析器上，
        // trusted-proxies 为空时它返回直连对端 127.0.0.1，于是这条请求会被判成"本机"而放行。
        assertThatThrownBy(() -> controller.notify("PAY-1", "SUCCESS", request("127.0.0.1", "203.0.113.7")))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("203.0.113.7");
        // 反代转发的本机请求（XFF 最右段仍是回环）仍然放行
        assertThat(controller.notify("PAY-1", "SUCCESS", request("127.0.0.1", "127.0.0.1")).getCode())
                .isZero();
    }

    @Test
    @DisplayName("bill/export 同样只对本机开放（批次43 补 F-08：同一次加固漏掉的面）")
    void 导出账单仅本机() {
        assertThatThrownBy(() -> controller.exportBill("2026-09-19", null, request("203.0.113.7", null)))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("仅限本机");
        assertThatThrownBy(() -> controller.exportBill("2026-09-19", null, request("127.0.0.1", "203.0.113.7")))
                .isInstanceOf(RRException.class);
        when(channelReconService.exportBillCsv("2026-09-19", null)).thenReturn("tradeNo,amount\n");
        assertThat(controller.exportBill("2026-09-19", null, request("127.0.0.1", null)))
                .isEqualTo("tradeNo,amount\n");
    }

    @Test
    @DisplayName("notify：非法 result 与非法单号被拒（白名单先于业务）")
    void 非法参数拒绝() {
        assertThatThrownBy(() -> controller.notify("PAY-1", "MAYBE", request("127.0.0.1", null)))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("SUCCESS/FAIL");
        assertThatThrownBy(() -> controller.notify("<script>", "SUCCESS", request("127.0.0.1", null)))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("格式非法");
        verify(payOrderService, never()).handleCallback(anyString(), anyString(), anyString());
    }
}
