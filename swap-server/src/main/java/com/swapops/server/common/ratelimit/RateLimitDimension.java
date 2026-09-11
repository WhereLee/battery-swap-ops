package com.swapops.server.common.ratelimit;

/**
 * 限流维度（S3.8 WP2）：
 * GLOBAL=整个集群一个桶（名称为粒度）；USER=按登录用户；IP=按客户端 IP（含 X-Forwarded-For）；
 * API=按方法签名（同端点不同实例共享一个桶）。
 */
public enum RateLimitDimension {
    GLOBAL,
    USER,
    IP,
    API
}
