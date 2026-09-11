package com.swapops.server.common.web;

import com.swapops.server.common.RRException;

/**
 * 当前请求用户上下文（用户端 token 拦截器写入，finally 清理防线程复用串号——沿用范例哲学）。
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    public static Long get() {
        return USER_ID.get();
    }

    /** 取当前用户；未认证即显式失败（调用方应保证在受保护路径内） */
    public static Long require() {
        Long userId = USER_ID.get();
        if (userId == null) {
            throw new RRException(401, "未认证");
        }
        return userId;
    }

    public static void clear() {
        USER_ID.remove();
    }

    private UserContext() {
    }
}
