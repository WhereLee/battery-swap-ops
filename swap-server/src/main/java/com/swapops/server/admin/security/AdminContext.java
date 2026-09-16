package com.swapops.server.admin.security;

import com.swapops.server.admin.enums.AdminRole;

import java.util.Set;

/**
 * 管理端当前身份（ThreadLocal；参照壳子 LoginUserHolder 模式，去 Spring Security 类型耦合）。
 * 使用纪律：AdminAuthFilter 在请求进入时 set、finally clear；服务层只读。
 */
public final class AdminContext {

    /**
     * 当前管理身份（bootstrap=静态 break-glass token，adminId 为 null）。
     * P1-8 数据范围：dataScope=ALL 时 scopeStationIds=null（不限）；STATION 时为解析后的站点 id 集合
     * （可能为空集=无任何可见数据，fail-closed）。
     */
    public record Principal(Long adminId, String username, AdminRole role, boolean bootstrap,
                            String dataScope, Set<Long> scopeStationIds) {

        /** 是否受站点数据范围约束（仅 STATION；bootstrap/ALL 不受限） */
        public boolean dataScoped() {
            return !bootstrap && "STATION".equalsIgnoreCase(dataScope);
        }
    }

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private AdminContext() {
    }

    /** 供 AdminAuthFilter（生产）与单测使用；服务层纪律：只读不写。 */
    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    static void clear() {
        HOLDER.remove();
    }

    public static Principal current() {
        return HOLDER.get();
    }

    public static Long currentAdminId() {
        Principal principal = HOLDER.get();
        return principal == null ? null : principal.adminId();
    }

    public static String currentUsername() {
        Principal principal = HOLDER.get();
        return principal == null ? null : principal.username();
    }

    public static String currentUsernameOr(String fallback) {
        String username = currentUsername();
        return username == null ? fallback : username;
    }
}
