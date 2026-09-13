package com.swapops.server.transfer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 调拨明细（S4.2）：一电池一行；(task_no,battery_no) 唯一=幂等。
 */
@Data
@TableName("transfer_task_item")
public class TransferTaskItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskNo;

    private String batteryNo;

    /** TransferItemStatus code */
    private Integer status;

    private Long outCellId;

    private Long inCellId;

    private Long outTime;

    private Long inTime;

    private Long createTime;

    private Long updateTime;
}
