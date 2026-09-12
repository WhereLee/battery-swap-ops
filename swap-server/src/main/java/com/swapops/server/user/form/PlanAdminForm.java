package com.swapops.server.user.form;

import lombok.Data;

/**
 * 管理端套餐表单（S4.5 批二）。
 */
@Data
public class PlanAdminForm {

    private String name;

    /** TIMES / MONTHLY */
    private String planType;

    private Integer priceFen;

    /** 次卡总次数（TIMES 必填 >0） */
    private Integer totalTimes;

    /** 月卡有效天数（MONTHLY 必填 >0） */
    private Integer durationDays;

    /** 日限次（可空=不限；>0） */
    private Integer dailyLimitTimes;
}
