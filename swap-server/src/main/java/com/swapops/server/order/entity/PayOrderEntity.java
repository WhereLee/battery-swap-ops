package com.swapops.server.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 充值支付单（S3.4）：外部资金入口的唯一凭证。
 * trade_no 唯一；status 由回调 CAS 推进（WAIT→SUCCESS/CLOSED），CAS 命中才入账（幂等闸）。
 */
@Data
@TableName("pay_order")
public class PayOrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台交易号（R 前缀） */
    private String tradeNo;

    private Long userId;

    private Integer amountFen;

    /** 用途：RECHARGE（预留扩展） */
    private String purpose;

    /** PayOrderStatus：WAIT / SUCCESS / CLOSED */
    private String status;

    /** 渠道：MOCK（不接真实渠道） */
    private String channel;

    /** 回调到达时间(ms) */
    private Long callbackTime;

    private Long createTime;

    private Long updateTime;
}
