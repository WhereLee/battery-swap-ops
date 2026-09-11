package com.swapops.server.device.form;

import lombok.Data;

/**
 * 事件上报（协议 v1 §2.2）。
 */
@Data
public class DeviceEventForm {

    private String cabinetNo;

    /** 事件类型（EventType wire 名） */
    private String eventType;

    /** 柜内仓号（按事件类型必填） */
    private Integer cellNo;

    /** 电池编号（出入仓/电量事件必填） */
    private String batteryNo;

    /** 电量（SOC_REPORT 必填 / 出入仓可带） */
    private Integer soc;

    /** 引起本次事件的指令 seq（可空：非指令驱动） */
    private Long commandSeq;

    private String bootId;

    private Long eventSeq;
}
