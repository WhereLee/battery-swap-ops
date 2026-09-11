package com.swapops.server.workorder.enums;

/**
 * 工单状态机（S4.4）：OPEN→TRIAGED→ASSIGNED→HANDLING→VERIFIED→CLOSED，只前向。
 */
public enum WorkOrderStatus {

    OPEN(1),
    TRIAGED(2),
    ASSIGNED(3),
    HANDLING(4),
    VERIFIED(5),
    CLOSED(6);

    private final int code;

    WorkOrderStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static WorkOrderStatus fromCode(Integer code) {
        for (WorkOrderStatus status : values()) {
            if (code != null && status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知工单状态: " + code);
    }
}
