package com.swapops.server.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 令牌桶限流（S3.8 WP2，标准实现）。
 *
 * <p>桶容量=permits（突发上限），补充速率=permits/windowSeconds；桶状态在 Redis（多实例共享、Lua 原子）。</p>
 * <p>语义：抢到令牌才执行；未抢到 429 + Retry-After，不执行方法体。
 * Redis 故障时 fail-open（只告警放行）——限流是保护不是正确性依赖。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流器名称（桶键前缀，跨端点可共享同一逻辑限流器） */
    String name();

    /** 维度（默认全局） */
    RateLimitDimension dimension() default RateLimitDimension.GLOBAL;

    /** 桶容量/突发上限（令牌数） */
    double permits();

    /** 补充周期秒（rate = permits / windowSeconds） */
    double windowSeconds();

    /**
     * 可选附加键（SpEL，方法参数可用；如 "#form.phone"），拼在维度值之后——
     * 用于"同维度再细分"（例如按手机号而非仅 IP 限流）。
     */
    String key() default "";
}
