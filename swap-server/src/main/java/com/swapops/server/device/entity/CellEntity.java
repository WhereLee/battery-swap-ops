package com.swapops.server.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 仓（槽位）：物理状态由事件驱动；lockOrderId 为分配预占的 DB 兜底（条件更新）。
 */
@Data
@TableName("cell")
public class CellEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long cabinetId;

    private Integer cellNo;

    /** CellStatus（1 空闲 / 2 占用 / 3 故障 / 4 停用） */
    private Integer status;

    /** 当前电池（展示冗余，写入以 battery 为准） */
    private Long batteryId;

    /** 预占订单（null=未锁定） */
    private Long lockOrderId;

    private Long updateTime;
}
