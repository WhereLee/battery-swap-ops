package com.swapops.server.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Agent 动作建议单（S4.6）：建议不产生副作用；人工确认后才执行（幂等+审计）。
 */
@Data
@TableName("agent_action")
public class AgentActionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String actionNo;

    private String idemKey;

    /** AgentActionType */
    private String actionType;

    private String paramsJson;

    private String reason;

    /** AgentActionStatus code */
    private Integer status;

    private String proposer;

    private String confirmer;

    private String resultJson;

    private String errorMsg;

    private String traceId;

    private Long createTime;

    private Long confirmTime;

    private Long updateTime;
}
