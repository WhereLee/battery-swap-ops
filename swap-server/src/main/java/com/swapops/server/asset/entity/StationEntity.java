package com.swapops.server.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("station")
public class StationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String stationNo;

    private String name;

    private String address;

    /** 1 运营 / 2 停用 */
    /** 纬度（调拨距离用，S4.2；可空） */
    private Double latitude;

    /** 经度（调拨距离用，S4.2；可空） */
    private Double longitude;

    private Integer status;

    /** 归属代理（S7 WP-B；NULL=直营） */
    private Long agentId;

    private Long createTime;

    private Long updateTime;
}
