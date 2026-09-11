package com.swapops.server.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 指令流水：seq 幂等键 + CAS 状态跃迁（编号唯一索引 uq_cabinet_seq 兜底）。
 */
@Data
@TableName("command_log")
public class CommandLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cabinetNo;

    /** CommandAction（OPEN_CELL/QUERY_STATE） */
    private String commandAction;

    private Long commandSeq;

    /** CommandStatus（1 PENDING / 2 ARRIVED / 3 SEND_FAILED / 4 RETRY_EXCEEDED / 5 SUPERSEDED） */
    private Integer commandStatus;

    private Integer retryCount;

    private String traceId;

    private Long createTime;

    private Long updateTime;
}
