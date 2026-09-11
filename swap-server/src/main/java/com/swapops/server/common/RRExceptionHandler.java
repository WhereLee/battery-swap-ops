package com.swapops.server.common;

import com.swapops.server.common.ratelimit.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常：业务异常 400 / 限流 429 + Retry-After / 显式状态透传（401 等）/ 其余 500（不吞成 200）。
 */
@Slf4j
@RestControllerAdvice
public class RRExceptionHandler {

    @ExceptionHandler(RRException.class)
    public ResponseEntity<Result<Void>> handleRR(RRException e) {
        return ResponseEntity.badRequest().body(Result.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Result<Void>> handleRateLimit(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(Result.error(429, e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> handleStatus(ResponseStatusException e) {
        log.warn("HTTP 状态异常透传 status={} reason={}", e.getStatusCode().value(), e.getReason());
        return ResponseEntity.status(e.getStatusCode())
                .body(Result.error(e.getStatusCode().value(), e.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.internalServerError().body(Result.error(500, "系统繁忙，请稍后重试"));
    }
}
