package com.swapops.server.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 管理员账号（S7 WP-A，db/11）：BCrypt 密码；角色见 AdminRole。
 */
@Data
@TableName("admin_user")
public class AdminUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String realName;

    /** SUPER/OPS/FINANCE/SUPPORT */
    private String role;

    /** 1 启用 / 2 停用 */
    private Integer status;

    /** P1-8 数据范围：ALL=全部（默认）/ STATION=按站点隔离 */
    private String dataScope;

    /** P1-8 数据范围：逗号分隔 station_no（STATION 时必填） */
    private String scopeStationNos;

    private Long lastLoginTime;

    private Long createTime;

    private Long updateTime;
}
