package com.swapops.server.common.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 过滤器/拦截器阶段（尚未进入 @RestControllerAdvice）的错误响应输出：
 * 保持与业务统一的 {code,msg} JSON 语义，避免过滤器直接裸 401/403 空响应。
 */
public final class WebErrors {

    public static void write(HttpServletResponse response, int status, int code, String msg) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + escape(msg) + "\"}");
    }

    private static String escape(String msg) {
        if (msg == null) {
            return "";
        }
        return msg.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private WebErrors() {
    }
}
