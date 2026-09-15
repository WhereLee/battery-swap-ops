package com.swapops.server.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 工单（S4.4）：alarm_id 唯一（一个告警一张工单，重转幂等返回既有）。
 */
@Data
@TableName("work_order")
public class WorkOrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String woNo;

    private Long alarmId;

    /** 来源（S7 WP-D）：ALARM / USER_REPORT */
    private String source;

    /** 报障用户（source=USER_REPORT 时非空） */
    private Long reporterUserId;

    /** 用户描述（报障原文） */
    private String description;

    private String deviceType;

    private String deviceNo;

    private String title;

    /** HIGH/MEDIUM/LOW */
    private String severity;

    /** WorkOrderStatus code */
    private Integer status;

    private Long handlerId;

    /** SLA 截止(ms) */
    private Long slaDeadline;

    private Integer slaBreached;

    private Long verifyTime;

    private Long closeTime;

    private String remark;

    private Long createTime;

    private Long updateTime;
}
