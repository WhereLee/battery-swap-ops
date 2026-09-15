package com.swapops.server.settlement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 代理商（S7 WP-B，db/12）：分成为 share_bp 万分比（0=全平台，10000=全代理）。
 */
@Data
@TableName("agent")
public class AgentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentNo;

    private String name;

    private String contact;

    /** 1 启用 / 2 停用 */
    private Integer status;

    /** 代理分成比例（万分比 0~10000） */
    private Integer shareBp;

    /** 记录口径：DAILY/WEEKLY/MONTHLY（v1 仅记录） */
    private String settlementCycle;

    private Long createTime;

    private Long updateTime;
}
