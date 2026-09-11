package com.swapops.server.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("swap_user")
public class SwapUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String phone;

    private String name;

    /** 1 正常 / 2 冻结 */
    private Integer status;

    private Long createTime;

    private Long updateTime;
}
