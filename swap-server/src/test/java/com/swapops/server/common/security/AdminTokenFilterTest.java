package com.swapops.server.common.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理端鉴权单测：合法/非法 token、路径旁路、未配置 fail-fast。
 */
@DisplayName("管理端静态 token 鉴权")
class AdminTokenFilterTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("合法 token：放行")
    void 合法token放行() throws ServletException, IOException {
        AdminTokenFilter filter = new AdminTokenFilter(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/station");
        request.setServletPath("/admin/station");
        request.addHeader("X-Admin-Token", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("错误/缺失 token：401 且不进入链路")
    void 错误token拒绝() throws ServletException, IOException {
        AdminTokenFilter filter = new AdminTokenFilter(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/station");
        request.setServletPath("/admin/station");
        request.addHeader("X-Admin-Token", "deadbeef");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("管理端未认证");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("非管理端路径：直接放行（不校验 token）")
    void 非管理端旁路() throws ServletException, IOException {
        AdminTokenFilter filter = new AdminTokenFilter(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/stations");
        request.setServletPath("/user/stations");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("未配置 token：启动即失败（管理面不以空凭证开局）")
    void 未配置failFast() {
        assertThatThrownBy(() -> new AdminTokenFilter(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("swap.admin.token");
    }
}
