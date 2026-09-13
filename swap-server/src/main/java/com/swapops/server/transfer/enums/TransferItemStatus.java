package com.swapops.server.transfer.enums;

/**
 * 调拨明细状态（S4.2）：PENDING→OUT（在途）→IN（入库）。
 */
public enum TransferItemStatus {

    PENDING(1),
    OUT(2),
    IN(3);

    private final int code;

    TransferItemStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static TransferItemStatus fromCode(Integer code) {
        for (TransferItemStatus status : values()) {
            if (code != null && status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知调拨明细状态: " + code);
    }
}
