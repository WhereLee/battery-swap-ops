package com.swapops.server.workorder.form;

import lombok.Data;

/** 用户报障表单（S7 WP-D）：类型 DEVICE_FAULT/CELL_FAULT/PAYMENT/OTHER。 */
@Data
public class UserReportForm {

    private String cabinetNo;

    /** 可选：具体仓位 */
    private Integer cellNo;

    private String type;

    private String description;
}
