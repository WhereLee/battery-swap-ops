package com.swapops.server.dev;

import com.swapops.server.common.RRException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 联调端点本机闸门测试（批次43 补：独立审计 F-07 / F-08 / F-15）。
 *
 * <p>核心回归：<b>缺省 trusted-proxies（仓库默认）+ 反代</b> 这一组合下，旧实现把远程调用判成本机。
 * 这里的用例全部<b>不配置任何限流属性</b>，因为新闸门刻意不依赖限流配置。
 */
@DisplayName("联调端点：本机调用闸门")
class DevLoopbackGuardTest {

    private final DevLoopbackGuard guard = new DevLoopbackGuard();

    private MockHttpServletRequest request(String remoteAddr, String forwardedFor, String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/dev/device/open");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        if (realIp != null) {
            request.addHeader("X-Real-IP", realIp);
        }
        return request;
    }

    @Test
    @DisplayName("真·直连本机（无转发头）放行")
    void 直连本机放行() {
        assertThat(guard.require(request("127.0.0.1", null, null), "dev/device/open")).isEqualTo("127.0.0.1");
        assertThat(guard.require(request("::1", null, null), "dev/device/open")).isEqualTo("::1");
    }

    @Test
    @DisplayName("直连地址非回环一律拒绝（远程 TCP 连接不可能来自回环）")
    void 直连远程拒绝() {
        assertThatThrownBy(() -> guard.require(request("203.0.113.7", null, null), "dev/device/open"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("203.0.113.7");
    }

    @Test
    @DisplayName("F-15 回归：缺省 trusted-proxies + 反代 ⇒ 远程调用必须被拒（旧实现此处 fail-open）")
    void 缺省配置下反代远程拒绝() {
        // nginx 形态：直连对端是 127.0.0.1，XFF 最右段是它追加的真实客户端。
        // 旧实现走 ClientIpResolver：trusted-proxies 为空 ⇒ 直接返回直连地址 127.0.0.1 ⇒ 判成本机 ⇒ 放行。
        assertThatThrownBy(() -> guard.require(request("127.0.0.1", "203.0.113.7", null), "pay/mock/notify"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("203.0.113.7");
    }

    @Test
    @DisplayName("本机客户端经本机反代仍然放行（联调浏览器走 ssh -L / 本机 nginx 的形态）")
    void 本机经反代放行() {
        assertThat(guard.require(request("127.0.0.1", "127.0.0.1", null), "dev/device/open")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("XFF 最右段才是代理背书过的对端：客户端自己伪造的前缀不影响判定")
    void 伪造前缀不影响判定() {
        // 攻击者在最左塞了一个回环地址想冒充本机；nginx 追加的真实对端在最右。
        assertThatThrownBy(() -> guard.require(request("127.0.0.1", "127.0.0.1, 203.0.113.7", null), "dev/device/open"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("203.0.113.7");
        // 反向：伪造一个远程前缀也不会把本机请求误判成远程。
        assertThat(guard.require(request("127.0.0.1", "203.0.113.7, 127.0.0.1", null), "dev/device/open"))
                .isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("只有 X-Real-IP 时同样按它判定")
    void 仅XRealIp时判定() {
        assertThatThrownBy(() -> guard.require(request("127.0.0.1", null, "203.0.113.7"), "dev/device/open"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("203.0.113.7");
        assertThat(guard.require(request("127.0.0.1", null, "127.0.0.1"), "dev/device/open")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("缺少 request / 缺少对端地址时 fail-closed")
    void 缺信息时拒绝() {
        assertThatThrownBy(() -> guard.require(null, "dev/device/open")).isInstanceOf(RRException.class);
        assertThatThrownBy(() -> guard.require(request(null, null, null), "dev/device/open"))
                .isInstanceOf(RRException.class);
    }
}
