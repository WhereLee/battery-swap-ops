package com.swapops.server.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 管理操作审计（S7 WP-A，db/11）：写操作留痕（参照壳子 sys_log 模式，仅写操作）。
 */
@Data
@TableName("admin_op_log")
public class AdminOpLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long adminId;

    private String username;

    private String action;

    private String method;

    private String uri;

    private String paramsJson;

    /** 0 成功 / 1 失败 */
    private Integer resultCode;

    private String error;

    private Long durationMs;

    private String ip;

    private Long createTime;
}
