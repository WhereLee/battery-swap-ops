package com.swapops.server.admin.security;

import com.swapops.server.admin.entity.AdminUserEntity;
import com.swapops.server.admin.service.AdminAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 管理端认证过滤器单测（S7 WP-A）：break-glass 静态 token / 会话 token / 无效 401 / 登录路径豁免 / 上下文清理。
 */
@DisplayName("管理端认证过滤器")
class AdminAuthFilterTest {

    private static final String STATIC_TOKEN = "0123456789abcdef0123456789abcdef";

    private AdminAuthService mockAuthService() {
        return mock(AdminAuthService.class);
    }

    private MockHttpServletRequest adminRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/alarm");
        request.setServletPath("/admin/alarm");
        if (token != null) {
            request.addHeader("X-Admin-Token", token);
        }
        return request;
    }

    @Test
    @DisplayName("break-glass 静态 token：bootstrap 身份（SUPER）并进入链路")
    void 静态token放行() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter(mockAuthService(), STATIC_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<AdminContext.Principal> during = new AtomicReference<>();

        filter.doFilter(adminRequest(STATIC_TOKEN), response, (req, res) ->
                during.set(AdminContext.current()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(during.get()).isNotNull();
        assertThat(during.get().bootstrap()).isTrue();
        assertThat(during.get().role().name()).isEqualTo("SUPER");
        assertThat(AdminContext.current()).isNull(); // 请求末清理（防线程复用串号）
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("会话 token：解析真实管理员身份")
    void 会话token放行() throws Exception {
        AdminAuthService authService = mockAuthService();
        AdminUserEntity user = new AdminUserEntity();
        user.setId(9L);
        user.setUsername("ops1");
        user.setRole("OPS");
        when(authService.resolve("sess-1")).thenReturn(user);

        AdminAuthFilter filter = new AdminAuthFilter(authService, STATIC_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<AdminContext.Principal> during = new AtomicReference<>();

        filter.doFilter(adminRequest("sess-1"), response, (req, res) ->
                during.set(AdminContext.current()));

        assertThat(during.get()).isNotNull();
        assertThat(during.get().adminId()).isEqualTo(9L);
        assertThat(during.get().username()).isEqualTo("ops1");
        assertThat(during.get().bootstrap()).isFalse();
    }

    @Test
    @DisplayName("无效 token：401 且不进入链路")
    void 无效token拒绝() throws Exception {
        AdminAuthService authService = mockAuthService();
        when(authService.resolve("bad")).thenReturn(null);

        AdminAuthFilter filter = new AdminAuthFilter(authService, STATIC_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(adminRequest("bad"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("无 token：放行交由安全链裁决（不在此处 401）")
    void 无token放行() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter(mockAuthService(), STATIC_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(adminRequest(null), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(AdminContext.current()).isNull();
    }

    @Test
    @DisplayName("登录路径豁免（不参与认证，避免 401 死锁）")
    void 登录路径豁免() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter(mockAuthService(), STATIC_TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/auth/login");
        request.setServletPath("/admin/auth/login");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("静态 token 未配置/非法：启动即失败")
    void 未配置failFast() {
        assertThatThrownBy(() -> new AdminAuthFilter(mockAuthService(), ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("swap.admin.token");
    }
}
