package com.swapops.contract;

/**
 * 设备事件目录（协议 v1，S0.3 §3）——双端共用；wire 值 = 枚举名。
 */
public enum EventType {
    /** 门已开（开仓指令销账锚点） */
    DOOR_OPENED,
    /** 电池被取出（取电） */
    BATTERY_OUT,
    /** 电池被放入（还电） */
    BATTERY_IN,
    /** 门关闭（审计/对账，不单独推进订单） */
    DOOR_CLOSED,
    /** 电量上报（满电判定） */
    SOC_REPORT,
    /** 仓位故障 */
    CELL_FAULT,
    /** 柜级故障 */
    CABINET_FAULT;

    public static EventType fromWire(String wire) {
        for (EventType t : values()) {
            if (t.name().equals(wire)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知事件类型: " + wire);
    }
}
