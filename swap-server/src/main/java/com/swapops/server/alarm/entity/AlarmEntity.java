package com.swapops.server.alarm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 告警（S3.6）：handled=0 未处理 / 1 已处理（自动恢复或人工）。
 */
@Data
@TableName("alarm")
public class AlarmEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备类型：CABINET / CELL / ORDER / JOB / SYSTEM */
    private String deviceType;

    private String deviceNo;

    /** AlarmType */
    private String alarmType;

    private String content;

    /** 0 未处理 / 1 已处理 */
    private Integer handled;

    /** 人工处理人（自动恢复为空） */
    private Long handler;

    private Long createTime;

    private Long handledTime;
}
