package com.swapops.server.payrecon.enums;

/** 差异类型（S7 WP-C）：渠道独有 / 平台独有 / 金额不符 / 状态不符。 */
public enum ReconDiffType {
    CHANNEL_ONLY,
    PLATFORM_ONLY,
    AMOUNT_MISMATCH,
    STATUS_MISMATCH
}
