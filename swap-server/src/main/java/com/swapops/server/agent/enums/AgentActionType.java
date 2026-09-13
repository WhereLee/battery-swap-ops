package com.swapops.server.agent.enums;

/**
 * Agent 动作白名单（S4.6）：只开放低风险、可解释、复用既有领域服务的运维动作。
 * 资金类（退款/扣费/支付回调）**明确不入白名单**——Agent 只能建议，钱的事必须人走原有资金链路。
 */
public enum AgentActionType {

    /** 由告警创建工单（params: alarmId, severity?, remark?） */
    CREATE_WORK_ORDER_FROM_ALARM,

    /** 派单（params: workOrderId, handlerId） */
    ASSIGN_WORK_ORDER,

    /** 触发日终对账（params: 无） */
    RUN_RECONCILE,

    /** 下发充电策略（params: cabinetNo, priority, windows[]） */
    APPLY_CHARGE_POLICY
}
