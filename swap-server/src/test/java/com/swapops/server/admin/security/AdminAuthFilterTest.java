package com.swapops.server.admin.security;

import com.swapops.server.admin.entity.AdminUserEntity;
import com.swapops.server.admin.service.AdminAuthService;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.asset.entity.StationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private StationDao mockStationDao() {
        return mock(StationDao.class);
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
        AdminAuthFilter filter = new AdminAuthFilter(mockAuthService(), mockStationDao(), STATIC_TOKEN);
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

        AdminAuthFilter filter = new AdminAuthFilter(authService, mockStationDao(), STATIC_TOKEN);
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

        AdminAuthFilter filter = new AdminAuthFilter(authService, mockStationDao(), STATIC_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(adminRequest("bad"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("无 token：放行交由安全链裁决（不在此处 401）")
    void 无token放行() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter(mockAuthService(), mockStationDao(), STATIC_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(adminRequest(null), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(AdminContext.current()).isNull();
    }

    @Test
    @DisplayName("登录路径豁免（不参与认证，避免 401 死锁）")
    void 登录路径豁免() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter(mockAuthService(), mockStationDao(), STATIC_TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/auth/login");
        request.setServletPath("/admin/auth/login");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("静态 token 未配置/非法：启动即失败")
    void 未配置failFast() {
        assertThatThrownBy(() -> new AdminAuthFilter(mockAuthService(), mockStationDao(), ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("swap.admin.token");
    }

    @Test
    @DisplayName("会话 token + STATION 范围：认证期解析站点 id 集，dataScoped=true")
    void 会话token站点范围解析() throws Exception {
        AdminAuthService authService = mockAuthService();
        AdminUserEntity user = new AdminUserEntity();
        user.setId(9L);
        user.setUsername("ops-st002");
        user.setRole("OPS");
        user.setDataScope("STATION");
        user.setScopeStationNos("ST-002, ST-003");
        when(authService.resolve("sess-st")).thenReturn(user);
        StationDao stationDao = mockStationDao();
        StationEntity s2 = new StationEntity();
        s2.setId(2L);
        s2.setStationNo("ST-002");
        StationEntity s3 = new StationEntity();
        s3.setId(3L);
        s3.setStationNo("ST-003");
        when(stationDao.selectList(any())).thenReturn(List.of(s2, s3));

        AdminAuthFilter filter = new AdminAuthFilter(authService, stationDao, STATIC_TOKEN);
        AtomicReference<AdminContext.Principal> during = new AtomicReference<>();
        filter.doFilter(adminRequest("sess-st"), new MockHttpServletResponse(), (req, res) ->
                during.set(AdminContext.current()));

        assertThat(during.get()).isNotNull();
        assertThat(during.get().dataScoped()).isTrue();
        assertThat(during.get().scopeStationIds()).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("STATION 范围解析异常：fail-closed 空集（不可见任何数据）")
    void 范围解析异常failClosed() throws Exception {
        AdminAuthService authService = mockAuthService();
        AdminUserEntity user = new AdminUserEntity();
        user.setId(9L);
        user.setUsername("ops-st002");
        user.setRole("OPS");
        user.setDataScope("STATION");
        user.setScopeStationNos("ST-002");
        when(authService.resolve("sess-st")).thenReturn(user);
        StationDao stationDao = mockStationDao();
        when(stationDao.selectList(any())).thenThrow(new RuntimeException("db down"));

        AdminAuthFilter filter = new AdminAuthFilter(authService, stationDao, STATIC_TOKEN);
        AtomicReference<AdminContext.Principal> during = new AtomicReference<>();
        filter.doFilter(adminRequest("sess-st"), new MockHttpServletResponse(), (req, res) ->
                during.set(AdminContext.current()));

        assertThat(during.get()).isNotNull();
        assertThat(during.get().dataScoped()).isTrue();
        assertThat(during.get().scopeStationIds()).isEmpty();
    }
}
