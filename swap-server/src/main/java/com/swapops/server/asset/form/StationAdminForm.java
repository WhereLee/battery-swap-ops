package com.swapops.server.asset.form;

import lombok.Data;

/**
 * 管理端站点表单（S4.5 批二）。
 */
@Data
public class StationAdminForm {

    /** 站点编号（创建必填；更新时不可变更） */
    private String stationNo;

    private String name;

    private String address;

    /** 纬度（可空；调拨距离用） */
    private Double latitude;

    /** 经度（可空；调拨距离用） */
    private Double longitude;

    /** 归属代理（S7 WP-B；可空=NULL 直营） */
    private Long agentId;
}
