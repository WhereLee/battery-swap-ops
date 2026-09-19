package com.swapops.server.common.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 限流配置（swap.ratelimit.*）：总开关 + 受信反向代理列表。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.ratelimit")
public class RateLimitProperties {

    /** 关闭后注解失效（联调/压测对照用） */
    private boolean enabled = true;

    /**
     * 受信反向代理（精确 IP 或 CIDR，如 {@code 127.0.0.1} 或 {@code 10.0.0.0/8}）。
     *
     * <p><b>默认空</b>＝任何 X-Forwarded-For 都不采信，限流按 TCP 对端地址分桶。
     * 只有确实部署了反向代理（本项目的云上形态是 nginx 反代 {@code /api}）才配置它，
     * 否则等于把撞库防护的分桶键交给攻击者控制——见 {@link ClientIpResolver}。
     */
    private List<String> trustedProxies = new ArrayList<>();
}
