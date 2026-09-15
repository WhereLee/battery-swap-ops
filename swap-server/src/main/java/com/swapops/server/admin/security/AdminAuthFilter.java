package com.swapops.server.admin.security;

import com.swapops.server.admin.entity.AdminUserEntity;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.service.AdminAuthService;
import com.swapops.server.common.web.WebErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理端认证过滤器（S7 WP-A，替代原 AdminTokenFilter；仅随 SecurityFilterChain 作用于 /admin/**）：
 * <ul>
 *   <li>会话 token（Redis）→ 真实管理员身份 + 角色权限码；</li>
 *   <li>静态 token（swap.admin.token）→ break-glass（SUPER 全权，审计 username=bootstrap，不可吊销）；</li>
 *   <li>登录路径豁免；无效 token 直接 401（与旧过滤器语义一致）；无 token 放行由 Security 链 401。</li>
 * </ul>
 * 身份写入 AdminContext（ThreadLocal）+ SecurityContext（authorities 供 @PreAuthorize）；请求末统一清理。
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Admin-Token";

    private final AdminAuthService adminAuthService;
    private final byte[] bootstrapToken;

    public AdminAuthFilter(AdminAuthService adminAuthService,
                           @Value("${swap.admin.token:}") String token) {
        if (token == null || !token.matches("^[0-9a-fA-F]{32,64}$")) {
            throw new IllegalStateException(
                    "swap.admin.token 未配置或格式非法（32~64 位 hex，经环境变量 SWAP_ADMIN_TOKEN 注入）");
        }
        this.adminAuthService = adminAuthService;
        this.bootstrapToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/admin/auth/login".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        try {
            if (token != null && !token.isBlank()) {
                if (MessageDigest.isEqual(bootstrapToken, token.getBytes(StandardCharsets.UTF_8))) {
                    authenticate(null, "bootstrap", AdminRole.SUPER, true);
                } else {
                    AdminUserEntity user = adminAuthService.resolve(token);
                    if (user == null) {
                        WebErrors.write(response, 401, 401, "管理端未认证");
                        return;
                    }
                    authenticate(user.getId(), user.getUsername(),
                            AdminRole.fromCode(user.getRole()), false);
                }
            }
            chain.doFilter(request, response);
        } finally {
            AdminContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(Long adminId, String username, AdminRole role, boolean bootstrap) {
        Set<String> perms = role.permissions();
        AdminContext.set(new AdminContext.Principal(adminId, username, role, bootstrap));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, null,
                perms.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
