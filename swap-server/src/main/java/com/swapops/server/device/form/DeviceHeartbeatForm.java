package com.swapops.server.device.form;

import lombok.Data;

/**
 * 心跳上报（协议 v1 §2.1）。
 */
@Data
public class DeviceHeartbeatForm {

    private String cabinetNo;

    /** 柜自述状态（1 在线/2 满载/3 故障/4 维护，可空） */
    private Integer status;

    /** 可选：代际（对账参考） */
    private String bootId;

    /** 可选：事件序（对账参考） */
    private Long eventSeq;
}
