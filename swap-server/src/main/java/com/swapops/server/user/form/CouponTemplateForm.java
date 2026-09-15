package com.swapops.server.user.form;

import lombok.Data;

/** 券模板创建表单（S7 WP-D，管理端；v1 仅 FIXED 立减）。 */
@Data
public class CouponTemplateForm {

    private String name;

    private Integer valueFen;

    private Integer minAmountFen;

    private Integer totalQuantity;

    private Integer perUserLimit;

    /** 有效期（天数，自创建起） */
    private Integer validDays;
}
