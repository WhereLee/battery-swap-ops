package com.swapops.contract;

/**
 * 仓物理状态（S0.2 §2）。
 */
public enum CellStatus {

    /** 空闲（无电池） */
    EMPTY(1),
    /** 占用（有电池） */
    OCCUPIED(2),
    /** 故障 */
    FAULT(3),
    /** 停用 */
    DISABLED(4);

    private final int code;

    CellStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static CellStatus fromCode(Integer code) {
        for (CellStatus s : values()) {
            if (code != null && s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知仓状态码: " + code);
    }
}
