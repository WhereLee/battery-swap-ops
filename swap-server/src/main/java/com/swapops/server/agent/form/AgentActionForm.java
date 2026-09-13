package com.swapops.server.agent.form;

import lombok.Data;

import java.util.Map;

/**
 * Agent 建议表单（S4.6）：params 由动作类型定义（见 MCP 工具清单文档）。
 */
@Data
public class AgentActionForm {

    /** AgentActionType 名称 */
    private String actionType;

    private Map<String, Object> params;

    private String reason;

    /** 提出方（如 agent-ops / admin） */
    private String proposer;
}
