package com.swapops.server.admin.enums;

import com.swapops.server.common.RRException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 管理端角色与权限码（S7 WP-A，参照壳子 RBAC 的权限串模型做静态化——无前端菜单，不做 sys_menu 动态表）。
 * 权限码命名：admin:{域}:{读|写类}；SUPER 全量；矩阵见 S7 方案 §三。
 */
public enum AdminRole {

    SUPER,
    OPS,
    FINANCE,
    SUPPORT;

    // ---------- 权限码常量（反射覆盖性测试据此校验注解用码合法） ----------
    public static final String ALARM_READ = "admin:alarm:read";
    public static final String ALARM_HANDLE = "admin:alarm:handle";
    public static final String WORK_ORDER_READ = "admin:work-order:read";
    public static final String WORK_ORDER_MANAGE = "admin:work-order:manage";
    public static final String ASSET_READ = "admin:asset:read";
    public static final String ASSET_MANAGE = "admin:asset:manage";
    public static final String TRANSFER_READ = "admin:transfer:read";
    public static final String TRANSFER_MANAGE = "admin:transfer:manage";
    public static final String CHARGE_POLICY_READ = "admin:charge-policy:read";
    public static final String CHARGE_POLICY_MANAGE = "admin:charge-policy:manage";
    public static final String PLAN_READ = "admin:plan:read";
    public static final String PLAN_MANAGE = "admin:plan:manage";
    public static final String ORDER_READ = "admin:order:read";
    public static final String REFUND_CREATE = "admin:refund:create";
    public static final String SETTLEMENT_READ = "admin:settlement:read";
    public static final String SETTLEMENT_MANAGE = "admin:settlement:manage";
    public static final String RECON_READ = "admin:recon:read";
    public static final String RECON_IMPORT = "admin:recon:import";
    public static final String RECON_HANDLE = "admin:recon:handle";
    public static final String USER_READ = "admin:user:read";
    public static final String USER_MANAGE = "admin:user:manage";
    public static final String ARREARS_READ = "admin:arrears:read";
    public static final String ARREARS_WAIVE = "admin:arrears:waive";
    public static final String COUPON_READ = "admin:coupon:read";
    public static final String COUPON_MANAGE = "admin:coupon:manage";
    public static final String REPORT_READ = "admin:report:read";
    public static final String DASHBOARD_READ = "admin:dashboard:read";
    public static final String CACHE_READ = "admin:cache:read";
    public static final String RATELIMIT_READ = "admin:ratelimit:read";
    public static final String RECONCILE_READ = "admin:reconcile:read";
    public static final String RECONCILE_RUN = "admin:reconcile:run";
    public static final String OPS_RUN = "admin:ops:run";
    public static final String SUGGESTION_READ = "admin:suggestion:read";
    public static final String SUGGESTION_MANAGE = "admin:suggestion:manage";
    public static final String AGENT_MGMT_READ = "admin:agent-mgmt:read";
    public static final String AGENT_MGMT_MANAGE = "admin:agent-mgmt:manage";
    public static final String ADMIN_MANAGE = "admin:admin:manage";

    /** 全量权限码（SUPER 与覆盖性测试用） */
    public static final Set<String> ALL_CODES = Set.of(
            ALARM_READ, ALARM_HANDLE, WORK_ORDER_READ, WORK_ORDER_MANAGE, ASSET_READ, ASSET_MANAGE,
            TRANSFER_READ, TRANSFER_MANAGE, CHARGE_POLICY_READ, CHARGE_POLICY_MANAGE,
            PLAN_READ, PLAN_MANAGE, ORDER_READ, REFUND_CREATE,
            SETTLEMENT_READ, SETTLEMENT_MANAGE, RECON_READ, RECON_IMPORT, RECON_HANDLE,
            USER_READ, USER_MANAGE, ARREARS_READ, ARREARS_WAIVE, COUPON_READ, COUPON_MANAGE,
            REPORT_READ, DASHBOARD_READ, CACHE_READ, RATELIMIT_READ,
            RECONCILE_READ, RECONCILE_RUN, OPS_RUN, SUGGESTION_READ, SUGGESTION_MANAGE,
            AGENT_MGMT_READ, AGENT_MGMT_MANAGE, ADMIN_MANAGE);

    /** 该角色的权限码集合 */
    public Set<String> permissions() {
        return switch (this) {
            case SUPER -> ALL_CODES;
            case OPS -> Set.of(ALARM_READ, ALARM_HANDLE, WORK_ORDER_READ, WORK_ORDER_MANAGE,
                    ASSET_READ, ASSET_MANAGE, TRANSFER_READ, TRANSFER_MANAGE,
                    CHARGE_POLICY_READ, CHARGE_POLICY_MANAGE, ORDER_READ, USER_READ,
                    DASHBOARD_READ, CACHE_READ, RATELIMIT_READ, OPS_RUN,
                    SUGGESTION_READ, SUGGESTION_MANAGE);
            case FINANCE -> Set.of(ALARM_READ, PLAN_READ, PLAN_MANAGE, ORDER_READ, REFUND_CREATE,
                    SETTLEMENT_READ, SETTLEMENT_MANAGE, RECON_READ, RECON_IMPORT, RECON_HANDLE,
                    USER_READ, ARREARS_READ, COUPON_READ, COUPON_MANAGE, REPORT_READ,
                    DASHBOARD_READ, RECONCILE_READ, RECONCILE_RUN,
                    AGENT_MGMT_READ, AGENT_MGMT_MANAGE);
            case SUPPORT -> Set.of(ALARM_READ, ALARM_HANDLE, WORK_ORDER_READ, WORK_ORDER_MANAGE,
                    ASSET_READ, ORDER_READ, USER_READ, ARREARS_READ, ARREARS_WAIVE,
                    DASHBOARD_READ);
        };
    }

    public static AdminRole fromCode(String raw) {
        try {
            return valueOf(raw == null ? "" : raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RRException("管理员角色非法: " + raw);
        }
    }

    /** 权限集合（去重、稳定序，供日志/测试用） */
    public static Set<String> union(AdminRole... roles) {
        Set<String> union = new LinkedHashSet<>();
        for (AdminRole role : roles) {
            union.addAll(role.permissions());
        }
        return union;
    }
}
