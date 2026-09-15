package com.swapops.server.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户券（S7 WP-D，db/14）：状态机 UNUSED→LOCKED→USED / 释放回 UNUSED / 惰性 EXPIRED；
 * 全状态跃迁 CAS（防并发双用）；expire_time 为领取时从模板快照。
 */
@Data
@TableName("user_coupon")
public class UserCouponEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long templateId;

    /** UserCouponStatus：1 UNUSED / 2 LOCKED / 3 USED / 4 EXPIRED */
    private Integer status;

    private Long lockedOrderId;

    private Long usedOrderId;

    /** 面额快照（发放时从模板复制；模板改价不影响已发券） */
    private Integer valueFen;

    /** 门槛快照（发放时从模板复制） */
    private Integer minAmountFen;

    private Long receivedTime;

    private Long lockedTime;

    private Long usedTime;

    private Long expireTime;
}
