package com.swapops.contract;

/**
 * 下行指令动作（协议 v1）。
 */
public enum CommandAction {
    /** 开仓（取/还电物理动作入口） */
    OPEN_CELL,
    /** 实况查询（S3 对账） */
    QUERY_STATE,
    /** 下发充电策略（S4.3：时段功率上限/费率/优先级；canonical 含策略版本） */
    SET_CHARGE_POLICY
}
