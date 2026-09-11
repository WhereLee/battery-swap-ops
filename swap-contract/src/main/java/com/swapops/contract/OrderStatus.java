package com.swapops.contract;

/**
 * 换电订单状态（S0.2 §4.2；S2 启用，S1 先落码值防漂移）。
 */
public enum OrderStatus {

    /** 待开仓（已预占） */
    PENDING_OPEN(1),
    /** 已开仓（等取/等还） */
    OPENED(2),
    /** 已取待还（SWAP） */
    TAKEN(3),
    /** 逾期占用（借出超时，可归还） */
    OVERDUE(4),
    /** 完成（终态） */
    COMPLETED(5),
    /** 取消（未取电） */
    CANCELLED(6),
    /** 超时关闭（终态） */
    TIMEOUT_CLOSED(7),
    /** 异常（人工介入） */
    EXCEPTION(8);

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static OrderStatus fromCode(Integer code) {
        for (OrderStatus s : values()) {
            if (code != null && s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知订单状态码: " + code);
    }
}
