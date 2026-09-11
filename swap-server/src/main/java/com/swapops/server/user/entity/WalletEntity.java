package com.swapops.server.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("wallet")
public class WalletEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 余额（分） */
    private Integer balanceFen;

    /** 押金（分） */
    private Integer depositFen;

    private Long updateTime;
}
