package com.swapops.server.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("plan")
public class PlanEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** TIMES 次卡 / MONTHLY 月卡 */
    private String planType;

    private Integer priceFen;

    /** 次卡总次数 */
    private Integer totalTimes;

    /** 月卡有效天数 */
    private Integer durationDays;

    /** 日限次（可空=不限） */
    private Integer dailyLimitTimes;

    /** 1 上架 / 2 下架 */
    private Integer status;

    private Long createTime;
}
