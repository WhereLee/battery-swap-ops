package com.swapops.contract;

/**
 * 柜上报状态（心跳 status 码；与 DB status 同码）。
 */
public enum CabinetStatus {

    /** 在线可用 */
    ONLINE(1),
    /** 满载（无空仓可还） */
    FULL(2),
    /** 故障 */
    FAULT(3),
    /** 维护 */
    MAINTENANCE(4),
    /** 人工停用（计划退役；不参与分配；心跳仍到但不再覆盖该状态） */
    DISABLED(5);

    private final int code;

    CabinetStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static CabinetStatus fromCode(Integer code) {
        for (CabinetStatus s : values()) {
            if (code != null && s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知柜状态码: " + code);
    }
}
