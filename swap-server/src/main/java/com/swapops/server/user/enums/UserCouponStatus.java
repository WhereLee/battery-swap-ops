package com.swapops.server.user.enums;

/** 用户券状态（S7 WP-D）：UNUSED → LOCKED（下单）→ USED（核销）/ 释放回 UNUSED；过期惰性置 EXPIRED。 */
public enum UserCouponStatus {
    UNUSED(1),
    LOCKED(2),
    USED(3),
    EXPIRED(4);

    private final int code;

    UserCouponStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
