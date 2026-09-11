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
    MAINTENANCE(4);

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
