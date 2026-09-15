package com.swapops.server.admin.security;

import com.swapops.server.admin.enums.AdminRole;

/**
 * 管理端当前身份（ThreadLocal；参照壳子 LoginUserHolder 模式，去 Spring Security 类型耦合）。
 * 使用纪律：AdminAuthFilter 在请求进入时 set、finally clear；服务层只读。
 */
public final class AdminContext {

    /** 当前管理身份（bootstrap=静态 break-glass token，adminId 为 null） */
    public record Principal(Long adminId, String username, AdminRole role, boolean bootstrap) {
    }

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private AdminContext() {
    }

    static void set(Principal principal) {
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
