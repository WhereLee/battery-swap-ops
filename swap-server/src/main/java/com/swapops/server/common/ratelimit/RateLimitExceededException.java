package com.swapops.server.common.ratelimit;

/**
 * 限流拒绝（S3.8 WP2）：全局异常处理器映射为 HTTP 429 + Retry-After 头。
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String limiterName, long retryAfterSeconds) {
        super("请求过于频繁，请稍后重试（限流器: " + limiterName + "）");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
