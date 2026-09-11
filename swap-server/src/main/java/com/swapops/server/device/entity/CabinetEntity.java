package com.swapops.server.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 换电柜（设备台账）：序号守卫基线（lastBootId/lastEventSeq）与密钥在此。
 */
@Data
@TableName("cabinet")
public class CabinetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cabinetNo;

    private Long stationId;

    private Integer cellCount;

    /** 1 在线 / 2 满载 / 3 故障 / 4 维护（CabinetStatus） */
    private Integer status;

    /** 设备密钥（32hex；仓库零明文，联调经环境变量注入） */
    private String secret;

    private String lastBootId;

    private Long lastEventSeq;

    private Long lastHeartbeatTime;

    private Long createTime;

    private Long updateTime;
}
