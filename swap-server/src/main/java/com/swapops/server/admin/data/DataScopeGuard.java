package com.swapops.server.admin.data;

import com.swapops.server.admin.security.AdminContext;

/**
 * 数据范围应用自检（P1-8）：与 {@code @DataFilter} 注解 + {@code DataFilterAspect} 配合。
 *
 * <p>语义：一次请求进入被 @DataFilter 标注的端点时 {@link #enter}；查询构建处调用
 * {@code DataScopeSupport.apply*} 会 {@link #markApplied}；请求结束时 {@link #exitAndCheck}
 * 若"受限身份但从未 apply"则返回端点标签（调用方记 warn）——防未来新增查询漏接过滤。
 * 本类是纯自检旁路：不阻断请求、不改变数据路径。</p>
 */
public final class DataScopeGuard {

    private static final ThreadLocal<String> ACTIVE_LABEL = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> APPLIED = new ThreadLocal<>();

    private DataScopeGuard() {
    }

    /** 进入 @DataFilter 端点（label 仅用于日志） */
    public static void enter(String label) {
        ACTIVE_LABEL.set(label == null ? "" : label);
        APPLIED.set(Boolean.FALSE);
    }

    /** 标记"本请求已应用数据范围过滤"（由 DataScopeSupport 调用） */
    public static void markApplied() {
        if (ACTIVE_LABEL.get() != null) {
            APPLIED.set(Boolean.TRUE);
        }
    }

    /**
     * 退出并检查。
     *
     * @return 需要告警的端点标签（受限身份但未 apply）；非受限身份/已 apply/无标记时一律 null。
     * 总是清理 ThreadLocal。
     */
    public static String exitAndCheck() {
        String label = ACTIVE_LABEL.get();
        Boolean applied = APPLIED.get();
        ACTIVE_LABEL.remove();
        APPLIED.remove();
        if (label == null || Boolean.TRUE.equals(applied)) {
            return null;
        }
        AdminContext.Principal principal = AdminContext.current();
        if (principal == null || !principal.dataScoped()) {
            return null; // 非受限身份本就不追加过滤，不告警
        }
        return label.isEmpty() ? "unknown" : label;
    }

    /** 请求末兜底清理（AdminAuthFilter finally 调用，防线程复用残留） */
    public static void clear() {
        ACTIVE_LABEL.remove();
        APPLIED.remove();
    }
}
