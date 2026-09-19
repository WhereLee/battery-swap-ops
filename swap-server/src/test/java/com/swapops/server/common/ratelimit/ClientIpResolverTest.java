package com.swapops.server.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端地址解析测试（批次33）：撞库防护的桶键不能被客户端 header 控制。
 *
 * <p>背景缺陷（独立审计 AUD-3）：限流取 {@code X-Forwarded-For} 左起第一段作为桶键，
 * 攻击者每请求换一个伪造值即每请求一个新桶 ⇒ 5 次/5 秒的登录限流等于不存在。
 */
@DisplayName("客户端地址解析（XFF 信任边界）")
class ClientIpResolverTest {

    private RateLimitProperties properties;
    private ClientIpResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        resolver = new ClientIpResolver(properties);
    }

    private MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/auth/login");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    @DisplayName("默认（无受信代理）：伪造的 XFF 一律忽略，桶键恒为 TCP 对端地址")
    void 默认忽略伪造XFF() {
        assertThat(resolver.resolve(request("203.0.113.7", "1.1.1.1"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("203.0.113.7", "9.9.9.9, 8.8.8.8"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("203.0.113.7", null))).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("攻击者视角：换 100 个 XFF 值也只落进同一个桶（这就是修复的意义）")
    void 换XFF不换桶() {
        java.util.Set<String> buckets = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            buckets.add(resolver.resolve(request("203.0.113.7", "10.0.0." + i)));
        }
        assertThat(buckets).as("无论伪造什么 XFF，桶键只有一个").hasSize(1);
    }

    @Test
    @DisplayName("受信代理（精确 IP）：取 XFF 最右非受信地址——nginx $proxy_add_x_forwarded_for 的口径")
    void 受信代理取最右侧() {
        properties.setTrustedProxies(List.of("127.0.0.1"));

        // 客户端自己塞了假的左段，nginx 在右边追加了真实对端 → 只能信最右
        assertThat(resolver.resolve(request("127.0.0.1", "1.2.3.4, 203.0.113.7")))
                .isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("127.0.0.1", "203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("受信代理链：跳过所有受信跳数，返回第一个非受信地址")
    void 多级代理链() {
        properties.setTrustedProxies(List.of("127.0.0.1", "10.0.0.0/8"));

        assertThat(resolver.resolve(request("127.0.0.1", "198.51.100.9, 10.1.2.3, 127.0.0.1")))
                .isEqualTo("198.51.100.9");
        assertThat(resolver.resolve(request("127.0.0.1", "10.1.2.3")))
                .as("整链都是受信代理 → 回落到直连地址")
                .isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("CIDR 匹配：IPv4 前缀边界正确（含 /32 与不命中）")
    void CIDR匹配() {
        properties.setTrustedProxies(List.of("10.0.0.0/8", "192.168.1.0/24", "172.16.0.0/12"));

        assertThat(resolver.isTrustedProxy("10.255.255.255")).isTrue();
        assertThat(resolver.isTrustedProxy("11.0.0.1")).isFalse();
        assertThat(resolver.isTrustedProxy("192.168.1.200")).isTrue();
        assertThat(resolver.isTrustedProxy("192.168.2.1")).isFalse();
        assertThat(resolver.isTrustedProxy("172.31.0.1")).isTrue();
        assertThat(resolver.isTrustedProxy("172.32.0.1")).isFalse();
    }

    @Test
    @DisplayName("脏配置与脏输入不抛异常：坏 CIDR / 空项 / 非法 IP 一律按不可信处理")
    void 脏值安全() {
        properties.setTrustedProxies(List.of("", "not-a-cidr/abc", "10.0.0.0/99", "999.999.999.999"));

        assertThat(resolver.isTrustedProxy("10.0.0.1")).isFalse();
        assertThat(resolver.isTrustedProxy(null)).isFalse();
        assertThat(resolver.isTrustedProxy("")).isFalse();
        assertThat(resolver.resolve(request("203.0.113.7", "1.1.1.1"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(null)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("对端地址缺失：返回常量 unknown（不返回 null，桶键不出现空串）")
    void 对端地址缺失() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/auth/login");
        request.setRemoteAddr(null);

        assertThat(resolver.resolve(request)).isEqualTo("unknown");
    }
}
