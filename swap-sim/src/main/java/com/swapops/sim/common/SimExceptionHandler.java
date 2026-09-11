package com.swapops.sim.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模拟器异常语义：参数错误 400、显式状态透传、其余 500——联调端点不再裸 500。
 */
@Slf4j
@RestControllerAdvice
public class SimExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(body(e.getStatusCode().value(), String.valueOf(e.getReason())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(400, String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.warn("sim 内部异常: {}", e.getMessage());
        return ResponseEntity.internalServerError().body(body(500, "sim internal error"));
    }

    private Map<String, Object> body(int code, String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("msg", msg);
        return result;
    }
}
