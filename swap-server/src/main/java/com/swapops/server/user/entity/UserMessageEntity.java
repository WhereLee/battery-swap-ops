package com.swapops.server.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 站内信（S7 WP-D，db/14）：券发放/欠费产生/退款到账/报障工单关闭四类触达。
 */
@Data
@TableName("user_message")
public class UserMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** REWARD / ARREARS / REFUND / WORK_ORDER */
    private String type;

    private String title;

    private String content;

    /** 0 未读 / 1 已读 */
    private Integer readFlag;

    private Long createTime;
}
