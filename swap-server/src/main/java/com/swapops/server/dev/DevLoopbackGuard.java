package com.swapops.server.dev;

import com.swapops.server.common.RRException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 联调端点的"本机调用"闸门（批次43 补：独立审计 F-07 / F-08 / F-15）。
 *
 * <p><b>为什么单独一个类</b>：批次36 把 mock 入账的回环判定寄生在限流用的 {@code ClientIpResolver} 上，
 * 而那个解析器的语义是"<b>默认不信任任何 X-Forwarded-For</b>"——在"trusted-proxies 为空（仓库默认值）
 * 且前面有反代"时，它返回的是<b>直连对端 127.0.0.1</b>，于是远程请求被判成"本机"，闸门 fail-open，
 * 与当时注释声称的恰好相反。也就是说：一个资金安全闸门的强度，取决于一个<b>限流</b>配置项的取值。
 * 这里把判定独立出来，只看两件事，且不需要任何配置：
 *
 * <ol>
 *   <li><b>直连对端必须是回环地址</b>——远程 TCP 连接不可能来自回环，这一条与代理无关；</li>
 *   <li><b>若存在转发头</b>（X-Forwarded-For / X-Real-IP），说明请求是经代理进来的，此时客户端地址取
 *       XFF 的<b>最右段</b>（nginx 的 {@code $proxy_add_x_forwarded_for} 把真实对端追加在最右），
 *       它必须是回环。于是：本机浏览器经本机反代访问仍然放行，远程客户端经同一个反代访问被拒。</li>
 * </ol>
 *
 * <p>边界（如实申报）：第 2 条依赖"反代会追加 XFF"。若某个代理被配成<b>透传</b>客户端自带的 XFF
 * 而不追加自己的对端，最右段就变成客户端可控的值——那种形态下这个闸门也会失效。要彻底消除该依赖，
 * 应改用"dev 端点独立口令/allowlist"（{@code swap.dev.secret} 目前配置了却从未用于鉴权），
 * 这属于部署形态决策，已列入批次43 记录的未做清单。
 */
@Slf4j
@Component
public class DevLoopbackGuard {

    /** 非本机调用时必须携带的请求头（值来自 {@code swap.dev.secret}）。 */
    public static final String DEV_SECRET_HEADER = "X-Dev-Secret";

    private final DevProperties devProperties;

    public DevLoopbackGuard(DevProperties devProperties) {
        this.devProperties = devProperties;
    }

    /**
     * 校验通过返回解析出的客户端地址（供日志），不通过抛 403 并打 WARN。
     *
     * <p>规则（批次43 补，F-07/F-08/F-15）：<b>本机闸门 OR 共享口令</b>，两者都不满足就拒绝。
     * 口令走 {@code swap.dev.secret}（该配置项此前存在但**从未被任何 dev 端点使用**）：
     * <ul>
     *   <li>本机调用（直连对端回环、无转发头）⇒ 放行，本地脚本/浏览器/模拟器不受影响；</li>
     *   <li>非本机调用 ⇒ 必须携带 {@code X-Dev-Secret} 且与配置值一致；<b>未配置口令时一律拒绝</b>（fail-closed）；</li>
     *   <li>口令比较用常量时间比较，避免时序侧信道（低风险，但成本为零）。</li>
     * </ul>
     * 这样"本机联调"和"给演示/CI 一个受控入口"两种形态都成立，而不需要把 dev 面直接暴露出去。
     *
     * @param endpoint 仅用于日志/报错文案，例如 {@code pay/mock/notify}
     */
    public String require(HttpServletRequest request, String endpoint) {
        String direct = request == null ? null : request.getRemoteAddr();
        boolean loopbackDirect = isLoopback(direct);
        String forwarded = header(request, "X-Forwarded-For");
        if (forwarded == null) {
            forwarded = header(request, "X-Real-IP");
        }
        String client = forwarded == null ? direct : rightmostHop(forwarded);
        if (loopbackDirect && forwarded == null) {
            // 真·直连本机（本地脚本 / 本机浏览器 / 本机压测），无代理参与。
            return direct;
        }
        if (isLoopback(client)) {
            // 经（本机）反代进来，但解析出的客户端仍是本机 —— 联调浏览器走 ssh -L / 本机 nginx 的形态。
            return client;
        }
        if (secretMatches(request)) {
            log.warn("[dev-loopback] 非本机调用凭 dev 口令放行 endpoint={} client={}", endpoint, client);
            return client;
        }
        return reject(endpoint, "既不是本机调用、也没有有效的 " + DEV_SECRET_HEADER, client);
    }

    /** 共享口令比对：未配置 = 不接受非本机调用（fail-closed）；比较用常量时间。 */
    private boolean secretMatches(HttpServletRequest request) {
        String configured = devProperties.getSecret();
        if (configured == null || configured.isBlank()) {
            return false;
        }
        String provided = header(request, DEV_SECRET_HEADER);
        if (provided == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                provided.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                configured.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String reject(String endpoint, String why, String client) {
        log.warn("[dev-loopback] 拒绝非本机调用 endpoint={} reason={} client={}", endpoint, why, client);
        throw new RRException(403, "联调端点仅限本机调用（" + why + "）: " + client);
    }

    private String header(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** XFF 的最右段（代理追加的真实对端）；单值头（X-Real-IP）原样返回。 */
    private String rightmostHop(String forwarded) {
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty()) {
                return hop;
            }
        }
        return forwarded.trim();
    }

    private boolean isLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)
                || "localhost".equals(ip);
    }
}
