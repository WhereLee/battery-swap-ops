package com.swapops.server.charge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 充电策略（S4.3）：版本化历史；ACTIVE=柜侧已确认应用；FAILED 可重投（同版本幂等）。
 */
@Data
@TableName("charge_policy")
public class ChargePolicyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cabinetNo;

    private Integer version;

    /** 窗口数组 JSON */
    private String policyJson;

    private Integer priority;

    /** 1 ACTIVE / 2 FAILED */
    private Integer status;

    private Long commandSeq;

    private String remark;

    private Long createTime;

    private Long updateTime;
}
