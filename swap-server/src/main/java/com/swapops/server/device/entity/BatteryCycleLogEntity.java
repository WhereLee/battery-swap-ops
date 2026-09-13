package com.swapops.server.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 电池循环流水（S4.1）：每次取出/归仓一条，唯一键 (boot_id,event_seq) 保证计数幂等、可审计。
 */
@Data
@TableName("battery_cycle_log")
public class BatteryCycleLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batteryNo;

    /** OUT 取出 / IN 归仓 */
    private String action;

    private Integer soc;

    private String cabinetNo;

    private Long commandSeq;

    private String bootId;

    private Long eventSeq;

    private Long createTime;
}
