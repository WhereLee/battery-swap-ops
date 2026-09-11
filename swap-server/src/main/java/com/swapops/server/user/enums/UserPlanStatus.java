package com.swapops.server.user.enums;

/**
 * 用户套餐状态。
 */
public enum UserPlanStatus {

    /** 生效 */
    ACTIVE(1),
    /** 过期 */
    EXPIRED(2),
    /** 退订 */
    REFUNDED(3),
    /** 次数用完 */
    USED_UP(4);

    private final int code;

    UserPlanStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
