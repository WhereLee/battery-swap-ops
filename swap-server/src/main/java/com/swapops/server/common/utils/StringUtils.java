package com.swapops.server.common.utils;

/**
 * 字符串小工具（避免散落 isBlank 判断口径不一致）。
 */
public final class StringUtils {

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    private StringUtils() {
    }
}
