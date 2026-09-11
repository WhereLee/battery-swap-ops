package com.swapops.server.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 工单流转日志（S4.4，审计）：每次动作一条，含前后状态与操作人。
 */
@Data
@TableName("work_order_log")
public class WorkOrderLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String woNo;

    private String action;

    private Integer fromStatus;

    private Integer toStatus;

    private String operator;

    private String remark;

    private Long createTime;
}
