package com.swapops.server.common;

import com.swapops.server.common.ratelimit.RateLimitExceededException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常：业务异常 400 / 限流 429 + Retry-After / 方法级鉴权拒绝 403 / 显式状态透传（401 等）/ 其余 500。
 */
@Slf4j
@RestControllerAdvice
public class RRExceptionHandler {

    @ExceptionHandler(RRException.class)
    public ResponseEntity<Result<Void>> handleRR(RRException e) {
        // code 为合法 HTTP 状态码（如 401）时透传，否则按业务异常 400
        int code = e.getCode();
        HttpStatus status = (code >= 400 && code <= 599) ? HttpStatus.valueOf(code) : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Result.error(code, e.getMessage()));
    }

    /** 方法级鉴权拒绝（@PreAuthorize）：403 而非 500（S7 WP-A） */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.error(403, "无权限执行该操作"));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Result<Void>> handleRateLimit(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(Result.error(429, e.getMessage()));
    }

    /** P1-7：@Valid 表单校验失败 → 400（首条字段错误）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse("参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(400, "参数校验失败: " + detail));
    }

    /** P1-7：方法参数校验（@Validated + @RequestParam 约束）失败 → 400。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getMessage())
                .orElse("参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(400, "参数校验失败: " + detail));
    }

    /** P1-7：请求体不是合法 JSON → 400（而非 500）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(400, "请求体不是合法 JSON"));
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
