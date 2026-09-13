package com.swapops.server.transfer.enums;

/**
 * 调拨任务状态（S4.2）：DRAFT→APPROVED→EXECUTING→DONE；DRAFT/APPROVED 可取消；EXECUTING 不可取消。
 */
public enum TransferStatus {

    DRAFT(1),
    APPROVED(2),
    EXECUTING(3),
    DONE(4),
    CANCELLED(5);

    private final int code;

    TransferStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static TransferStatus fromCode(Integer code) {
        for (TransferStatus status : values()) {
            if (code != null && status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知调拨状态: " + code);
    }
}
