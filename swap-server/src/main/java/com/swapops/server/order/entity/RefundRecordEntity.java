package com.swapops.server.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 退款单（S3.4）：资金出口的幂等闸 = unique(order_id, reason)。
 * 状态 WAIT→SUCCESS；失败留 WAIT 并延迟重投，超限进死信（S3.6 告警）。
 */
@Data
@TableName("refund_record")
public class RefundRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 退款单号（RF 前缀；延迟重试 taskId） */
    private String refundNo;

    private Long orderId;

    private Long userId;

    private Integer amountFen;

    /** 退款原因（同订单同原因仅一次）：ORDER_EXCEPTION / ADMIN_MANUAL */
    private String reason;

    /** RefundStatus：WAIT / SUCCESS */
    private String status;

    private String failReason;

    /** 人工退款操作人（admin_user.id；补偿通道为 null）——S7 WP-A */
    private Long operatorId;

    /** 操作人用户名快照（S7 WP-A） */
    private String operatorName;

    private Long createTime;

    private Long updateTime;
}
