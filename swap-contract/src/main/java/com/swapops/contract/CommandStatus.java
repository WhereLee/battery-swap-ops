package com.swapops.contract;

/**
 * 指令流水状态（沿用范例状态跃迁语义）。
 */
public enum CommandStatus {

    /** 待到位（在途） */
    PENDING(1),
    /** 已销账（设备事件证据驱动） */
    ARRIVED(2),
    /** 下发失败（显式失败，不重试） */
    SEND_FAILED(3),
    /** 重试超限（转人工） */
    RETRY_EXCEEDED(4),
    /** 被更新指令取代 */
    SUPERSEDED(5),
    /** 设备故障中断（FAULT 证据：在途指令永不执行） */
    EXEC_FAILED(6);

    private final int code;

    CommandStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static CommandStatus fromCode(Integer code) {
        for (CommandStatus s : values()) {
            if (code != null && s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知指令状态码: " + code);
    }
}
