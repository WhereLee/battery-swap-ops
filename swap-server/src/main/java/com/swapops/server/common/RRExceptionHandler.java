package com.swapops.server.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常：业务异常 400 / 显式状态透传（401/429 等）/ 其余 500（不吞成 200）。
 */
@Slf4j
@RestControllerAdvice
public class RRExceptionHandler {

    @ExceptionHandler(RRException.class)
    public ResponseEntity<Result<Void>> handleRR(RRException e) {
        return ResponseEntity.badRequest().body(Result.error(e.getCode(), e.getMessage()));
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
