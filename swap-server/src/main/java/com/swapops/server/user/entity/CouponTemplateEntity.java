package com.swapops.server.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 券模板（S7 WP-D，db/14）：v1 仅 FIXED 立减（分）；发行量 issued_count 以条件 UPDATE 扣减。
 */
@Data
@TableName("coupon_template")
public class CouponTemplateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** FIXED=立减（分） */
    private String type;

    private Integer valueFen;

    /** 使用门槛（订单基础费需 >= 该值） */
    private Integer minAmountFen;

    private Integer totalQuantity;

    private Integer issuedCount;

    private Integer perUserLimit;

    private Long validFrom;

    private Long validTo;

    /** 1 启用 / 2 停用 */
    private Integer status;

    private Long createTime;
}
