package com.swapops.server.asset.form;

import lombok.Data;

/**
 * 管理端电池登记表单（S4.5 批二）：
 * cellId 为空且 park=true → 在途；cellId 有值 → 放置到目标空仓。
 */
@Data
public class BatteryAdminForm {

    /** 电池编号（创建必填；更新时不可变更） */
    private String batteryNo;

    private String model;

    private Integer soc;

    private Integer soh;

    private Integer cycleCount;

    /** 目标仓（放置/改位；更新时不传=保持不变） */
    private Long cellId;

    /** 更新时显式转"在途"（与 cellId 互斥，cellId 优先） */
    private Boolean park;
}
