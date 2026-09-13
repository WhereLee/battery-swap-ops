package com.swapops.server.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 电池（流动资产）：cellId 与 holderUserId 互斥且分别唯一（S0.5 C1/C2）。
 */
@Data
@TableName("battery")
public class BatteryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batteryNo;

    private String model;

    /** BatteryStatus（1 充电 / 2 满电 / 3 借出 / 4 维修 / 5 退役） */
    private Integer status;

    private Integer soc;

    private Integer soh;

    private Integer cycleCount;

    /** 取出次数（换电服务次数，S4.1；来源 BATTERY_OUT 事件，流水可审计） */
    private Integer swaps;

    private Long cellId;

    private Long holderUserId;

    private Long updateTime;
}
