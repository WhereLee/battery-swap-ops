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
    private Integer status;

    private Long createTime;

    private Long updateTime;
}
