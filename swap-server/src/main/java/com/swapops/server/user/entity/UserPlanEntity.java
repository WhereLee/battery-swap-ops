package com.swapops.server.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user_plan")
public class UserPlanEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long planId;

    private Long startTime;

    private Long endTime;

    /** 次卡剩余次数（月卡为空） */
    private Integer remainingTimes;

    /** UserPlanStatus：1 生效 / 2 过期 / 3 退订 / 4 用完 */
    private Integer status;

    /** 购买幂等键（唯一；防重复下单重复扣费） */
    private String idemKey;

    private Long createTime;

    private Long updateTime;
}
