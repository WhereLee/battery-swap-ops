package com.swapops.server.admin.controller;

import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import com.swapops.server.common.RRException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 当前身份接口测试（S8 前端接入地基）：权限码与数据范围必须如实下发，
 * 前端路由/按钮据此渲染——漏码或错序都会让页面少按钮或多按钮。
 */
@DisplayName("当前身份 GET /admin/auth/me")
class AdminAuthMeTest {

    private final AdminAuthController controller = new AdminAuthController(null);

    @AfterEach
    void tearDown() {
        AdminContext.set(null);
    }

    @Test
    @DisplayName("SUPER（break-glass）：全量码 + 稳定序 + 不受数据范围限制")
    void 超管返回全量码() {
        AdminContext.set(new AdminContext.Principal(null, "bootstrap", AdminRole.SUPER, true, "ALL", null));

        Map<String, Object> data = controller.me().getData();

        assertThat(data.get("username")).isEqualTo("bootstrap");
        assertThat(data.get("role")).isEqualTo("SUPER");
        assertThat(data.get("bootstrap")).isEqualTo(true);
        assertThat(data.get("dataScoped")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) data.get("codes");
        assertThat(codes).hasSize(AdminRole.ALL_CODES.size()).isSorted();
    }

    @Test
    @DisplayName("OPS + STATION 范围：只回该角色权限码，站点范围升序下发")
    void 运营角色回子集与站点范围() {
        AdminContext.set(new AdminContext.Principal(9L, "ops01", AdminRole.OPS, false,
                "STATION", Set.of(9L, 3L)));

        Map<String, Object> data = controller.me().getData();

        assertThat(data.get("role")).isEqualTo("OPS");
        assertThat(data.get("dataScope")).isEqualTo("STATION");
        assertThat(data.get("dataScoped")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) data.get("codes");
        @SuppressWarnings("unchecked")
        List<Long> stationIds = (List<Long>) data.get("scopeStationIds");
        assertThat(codes).contains(AdminRole.OPS.permissions().toArray(new String[0]))
                .containsExactlyInAnyOrderElementsOf(AdminRole.OPS.permissions())
                .doesNotContain(AdminRole.SETTLEMENT_MANAGE, AdminRole.RECON_IMPORT)
                .isSorted();
        assertThat(stationIds).containsExactly(3L, 9L); // 升序（下发稳定，前端可直接用于展示）
    }

    @Test
    @DisplayName("无身份上下文：401（正常情况下过滤器已拦，此处为兜底）")
    void 无身份拒绝() {
        AdminContext.set(null);

        assertThatThrownBy(() -> controller.me())
                .isInstanceOf(RRException.class)
                .hasMessageContaining("管理端未认证");
    }
}
