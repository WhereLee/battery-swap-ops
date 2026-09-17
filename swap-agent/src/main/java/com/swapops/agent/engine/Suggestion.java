package com.swapops.agent.engine;

/**
 * 一条"建议"（零副作用）：由规则引擎产出，可被提交为平台建议单（propose）。
 * 执行必须人工在管理端 confirm——Agent 无自动执行通道。
 */
public record Suggestion(String actionType, long alarmId, String deviceNo, String alarmType,
                         String severity, int groupCount, String reason) {

    /** 幂等键：同告警 + 同动作只建议一次（平台 agent_action.idem_key 唯一键兜底）。 */
    public String idemKey() {
        return "agent-" + alarmId + "-" + actionType.toLowerCase();
    }
}
