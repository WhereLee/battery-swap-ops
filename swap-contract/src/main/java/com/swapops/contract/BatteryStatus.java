package com.swapops.contract;

/**
 * 电池生命周期状态（S0.2 §3）。
 */
public enum BatteryStatus {

    /** 在仓充电中（soc 未达阈值） */
    CHARGING(1),
    /** 在仓满电待换（可被分配） */
    FULL(2),
    /** 借出（在用户手中） */
    LOANED(3),
    /** 维修 */
    REPAIR(4),
    /** 退役（终态） */
    RETIRED(5);

    private final int code;

    BatteryStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static BatteryStatus fromCode(Integer code) {
        for (BatteryStatus s : values()) {
            if (code != null && s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知电池状态码: " + code);
    }
}
