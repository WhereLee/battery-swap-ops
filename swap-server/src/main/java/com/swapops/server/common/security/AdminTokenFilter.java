package com.swapops.server.common.security;

import com.swapops.server.common.web.WebErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理端静态 token 鉴权（P0 取舍见 S0.4 §3：RBAC 复用范例、留 S4）。
 *
 * <p>无默认口令：`swap.admin.token` 未配置（或格式非法）启动即失败——管理面绝不以空凭证开局。
 * 比对用常量时间 MessageDigest.isEqual 防时序侧信道；仅拦截 /admin/**。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminTokenFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Admin-Token";
    private static final String TOKEN_PATTERN = "^[0-9a-fA-F]{32,64}$";

    private final byte[] expected;

    public AdminTokenFilter(@Value("${swap.admin.token:}") String token) {
        if (token == null || !token.matches(TOKEN_PATTERN)) {
            throw new IllegalStateException(
                    "swap.admin.token 未配置或格式非法（32~64 位 hex，经环境变量 SWAP_ADMIN_TOKEN 注入）");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
            WebErrors.write(response, 401, 401, "管理端未认证");
            return;
        }
        chain.doFilter(request, response);
    }
}
