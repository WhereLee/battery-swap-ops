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

    /**
     * 客户端错误按语义返回 4xx，不要落进 {@link #handleOther} 变成 500（批次43 补，AUD-22）。
     *
     * <p>实测触发：用 GET 打 `@PostMapping` 端点 → 500；springdoc 关闭后访问 `/api/v3/api-docs` → 500。
     * 这两件事都不是服务端故障，但兜底分支把它们都变成了"系统繁忙"——**监控会把客户端错误当故障告警**，
     * 排障时也会先怀疑服务。行业惯例是框架级异常各归其位：
     * <ul>
     *   <li>路径/静态资源不存在 → 404（springdoc 关闭时的文档端点就落这里）</li>
     *   <li>方法不支持 → 405</li>
     *   <li>媒体类型/参数类型/缺参 → 415 / 400</li>
     * </ul>
     * 兜底 {@code Exception} 只留真正未预期的异常（保持 500 + 固定文案，不外泄栈）。
     */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class,
    })
    public ResponseEntity<Result<Void>> handleNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "资源不存在"));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.error(405, "请求方法不支持: " + e.getMethod()));
    }

    @ExceptionHandler({
            org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotAcceptableException.class,
    })
    public ResponseEntity<Result<Void>> handleMediaType(Exception e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.error(415, "请求/响应的媒体类型不支持"));
    }

    @ExceptionHandler({
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(400, "请求参数不合法: " + e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.internalServerError().body(Result.error(500, "系统繁忙，请稍后重试"));
    }
}
