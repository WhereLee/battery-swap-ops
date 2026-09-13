package com.swapops.server.asset.form;

import lombok.Data;

/**
 * 管理端柜表单（S4.5 批二）：secret 仅写入不返回（响应脱敏）。
 */
@Data
public class CabinetAdminForm {

    /** 柜编号（创建必填；更新时不可变更） */
    private String cabinetNo;

    private Long stationId;

    /** 仓位数（1~48；更新时增加补建/减少仅允许空仓） */
    private Integer cellCount;

    /** 设备密钥（32~64 hex；创建必填，更新=轮换可选） */
    private String secret;
}
