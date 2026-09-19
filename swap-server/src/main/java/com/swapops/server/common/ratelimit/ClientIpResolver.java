package com.swapops.server.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 客户端地址解析（批次33）：<b>默认不信任任何 X-Forwarded-For</b>。
 *
 * <p>为什么需要它：管理端登录的撞库防护只有一条——{@code @RateLimit(dimension = IP)}。
 * 而限流桶键一旦直接取 {@code X-Forwarded-For} 的<b>左起第一段</b>，攻击者每个请求换一个
 * 伪造值就得到一个全新的令牌桶，5 次/5 秒的限制形同不存在（一行 header 绕过撞库防护）。
 *
 * <p>规则：
 * <ol>
 *   <li>直连地址不在受信代理列表 ⇒ <b>完全不看</b> XFF，只用 {@code getRemoteAddr()}
 *       （部署形态是 systemd 直连时，任何 XFF 都是客户端自己编的）；</li>
 *   <li>直连地址是受信代理 ⇒ 从 XFF <b>最右侧</b>往左找第一个"非受信"地址——
 *       nginx 的 {@code $proxy_add_x_forwarded_for} 是"客户端伪造的整串 + 真实对端"，
 *       所以只有最右段是代理背书过的，左侧全部不可信；</li>
 *   <li>XFF 缺失或整条链都是受信代理 ⇒ 回落到直连地址（本机压测/健康检查等场景）。</li>
 * </ol>
 *
 * <p>受信代理用精确 IP 或 CIDR 配置（{@code swap.ratelimit.trusted-proxies}）。
 * <b>默认空列表</b>是刻意的安全缺省：宁可限流按代理地址聚合（粗但真实），
 * 也不要按客户端可伪造的值分桶（细但可绕过）。
 */
@Component
public class ClientIpResolver {

    private final RateLimitProperties properties;

    public ClientIpResolver(RateLimitProperties properties) {
        this.properties = properties;
    }

    /** 解析用于限流分桶的客户端地址；无法判定时返回常量 {@code unknown}。 */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return "unknown";
        }
        if (!isTrustedProxy(remote)) {
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty()) {
                continue;
            }
            if (!isTrustedProxy(hop)) {
                return hop;
            }
        }
        return remote;
    }

    /** 精确匹配或 CIDR 命中（IPv4/IPv6 通用，按字节前缀比较）。 */
    public boolean isTrustedProxy(String ip) {
        List<String> trusted = properties.getTrustedProxies();
        if (trusted == null || trusted.isEmpty() || ip == null || ip.isBlank()) {
            return false;
        }
        for (String entry : trusted) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String rule = entry.trim();
            if (rule.equals(ip)) {
                return true;
            }
            int slash = rule.indexOf('/');
            if (slash > 0 && matchesCidr(ip, rule.substring(0, slash), rule.substring(slash + 1))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCidr(String ip, String network, String prefixText) {
        int prefix;
        try {
            prefix = Integer.parseInt(prefixText.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        try {
            byte[] address = InetAddress.getByName(ip).getAddress();
            byte[] base = InetAddress.getByName(network).getAddress();
            if (address.length != base.length || prefix < 0 || prefix > address.length * 8) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainderBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != base[i]) {
                    return false;
                }
            }
            if (remainderBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainderBits) & 0xFF;
            return (address[fullBytes] & mask) == (base[fullBytes] & mask);
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
