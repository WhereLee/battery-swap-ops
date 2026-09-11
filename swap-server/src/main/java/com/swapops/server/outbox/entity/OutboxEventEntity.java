package com.swapops.server.outbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 本地消息表（S3.8 WP6）：与业务写同事务落库，中继任务补投 MQ——进程/网络故障不丢事件。
 */
@Data
@TableName("outbox_event")
public class OutboxEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键（业务唯一；重复入队被唯一键拒绝） */
    private String eventKey;

    /** 事件类型（路由发布器，如 ALARM） */
    private String eventType;

    /** 事件体 JSON */
    private String payload;

    /** NEW / SENT / DEAD */
    private String status;

    private Integer attempts;

    /** 下次尝试时间(ms) */
    private Long nextRetryTime;

    private String traceId;

    private String lastError;

    private Long createTime;

    private Long updateTime;

    private Long sentTime;
}
