package com.swapops.server.transfer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 调拨任务（S4.2）：状态由明细聚合推进（首次出库→EXECUTING；全入库→DONE）。
 */
@Data
@TableName("transfer_task")
public class TransferTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskNo;

    private Long fromStation;

    private Long toStation;

    private Integer planCount;

    /** TransferStatus code */
    private Integer status;

    private String createdBy;

    private String approvedBy;

    private String remark;

    private Long createTime;

    private Long updateTime;
}
