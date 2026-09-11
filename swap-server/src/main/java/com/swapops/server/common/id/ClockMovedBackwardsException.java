package com.swapops.server.common.id;

/**
 * 时钟回拨超出容忍窗口（Snowflake 拒绝生成，防止重复 ID）。
 * 调用方告警交人工；等待型小幅回拨由生成器内部自旋消化。
 */
public class ClockMovedBackwardsException extends RuntimeException {

    public ClockMovedBackwardsException(String message) {
        super(message);
    }
}
