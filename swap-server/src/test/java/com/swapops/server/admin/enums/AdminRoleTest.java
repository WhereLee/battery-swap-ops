package com.swapops.server.admin.enums;

import com.swapops.server.common.RRException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 角色权限矩阵单测（S7 WP-A）：SUPER 全量、角色边界（OPS 无资金码、FINANCE 无资产写等）。
 */
@DisplayName("管理端角色权限矩阵")
class AdminRoleTest {

    @Test
    @DisplayName("SUPER 拥有全部权限码")
    void super全量() {
        assertThat(AdminRole.SUPER.permissions()).isEqualTo(AdminRole.ALL_CODES);
        assertThat(AdminRole.SUPER.permissions()).contains(
                AdminRole.REFUND_CREATE, AdminRole.SETTLEMENT_MANAGE, AdminRole.ADMIN_MANAGE);
    }

    @Test
    @DisplayName("OPS：有资产/调拨/告警/工单，无资金与管理员管理")
    void ops边界() {
        assertThat(AdminRole.OPS.permissions()).contains(
                AdminRole.ASSET_MANAGE, AdminRole.TRANSFER_MANAGE, AdminRole.WORK_ORDER_MANAGE,
                AdminRole.ALARM_HANDLE, AdminRole.OPS_RUN);
        assertThat(AdminRole.OPS.permissions()).doesNotContain(
                AdminRole.REFUND_CREATE, AdminRole.PLAN_MANAGE, AdminRole.SETTLEMENT_MANAGE,
                AdminRole.ADMIN_MANAGE, AdminRole.RECON_IMPORT);
    }

    @Test
    @DisplayName("FINANCE：资金域全量（退款/结算/对账/券），无资产写与工单")
    void finance边界() {
        assertThat(AdminRole.FINANCE.permissions()).contains(
                AdminRole.REFUND_CREATE, AdminRole.SETTLEMENT_MANAGE, AdminRole.RECON_IMPORT,
                AdminRole.RECON_HANDLE, AdminRole.PLAN_MANAGE, AdminRole.COUPON_MANAGE,
                AdminRole.AGENT_MGMT_MANAGE);
        assertThat(AdminRole.FINANCE.permissions()).doesNotContain(
                AdminRole.ASSET_MANAGE, AdminRole.TRANSFER_MANAGE, AdminRole.WORK_ORDER_MANAGE,
                AdminRole.ADMIN_MANAGE, AdminRole.OPS_RUN);
    }

    @Test
    @DisplayName("SUPPORT：工单/告警处理/欠费减免，无资金写与资产写")
    void support边界() {
        assertThat(AdminRole.SUPPORT.permissions()).contains(
                AdminRole.WORK_ORDER_MANAGE, AdminRole.ALARM_HANDLE, AdminRole.ARREARS_WAIVE,
                AdminRole.ASSET_READ, AdminRole.ORDER_READ);
        assertThat(AdminRole.SUPPORT.permissions()).doesNotContain(
                AdminRole.REFUND_CREATE, AdminRole.ASSET_MANAGE, AdminRole.ADMIN_MANAGE,
                AdminRole.SETTLEMENT_READ);
    }

    @Test
    @DisplayName("非法角色码：业务异常")
    void 非法角色() {
        assertThatThrownBy(() -> AdminRole.fromCode("ROOT"))
                .isInstanceOf(RRException.class).hasMessageContaining("角色非法");
        assertThatThrownBy(() -> AdminRole.fromCode(null))
                .isInstanceOf(RRException.class);
    }
}
