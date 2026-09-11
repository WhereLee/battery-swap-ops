package com.swapops.server.common.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流总开关（swap.ratelimit.*）；限流粒度由注解声明（本配置只管启停）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.ratelimit")
public class RateLimitProperties {

    /** 关闭后注解失效（联调/压测对照用） */
    private boolean enabled = true;
}
