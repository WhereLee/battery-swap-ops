package com.swapops.server.common.utils;

/**
 * 分页参数解析与钳制（沿用范例 T15 语义）：非法输入钳制到边界、超大 limit 截断，
 * 绝不放行无界大页（查询接口翻大页是拖库面）。
 */
public final class PageParams {

    /** 单页上限 */
    public static final int MAX_LIMIT = 200;

    public static int page(Integer raw) {
        if (raw == null || raw < 1) {
            return 1;
        }
        return raw;
    }

    public static int limit(Integer raw) {
        if (raw == null || raw < 1) {
            return 10;
        }
        return Math.min(MAX_LIMIT, raw);
    }

    private PageParams() {
    }
}
