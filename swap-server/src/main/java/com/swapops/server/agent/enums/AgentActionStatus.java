package com.swapops.server.agent.enums;

/**
 * Agent 建议单状态（S4.6）：
 * PROPOSED --confirm(CAS 抢执行权)--> EXECUTING --成功--> EXECUTED
 *                                              \--异常--> FAILED
 * PROPOSED --reject--> REJECTED
 */
public enum AgentActionStatus {

    PROPOSED(1),
    EXECUTED(2),
    REJECTED(3),
    FAILED(4),
    EXECUTING(5);

    private final int code;

    AgentActionStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static AgentActionStatus fromCode(Integer code) {
        for (AgentActionStatus status : values()) {
            if (code != null && status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知建议单状态: " + code);
    }
}
