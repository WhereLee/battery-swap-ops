package com.swapops.server.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("payment_record")
public class PaymentRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 关联订单（充值/购套餐为空） */
    private Long orderId;

    /** PaymentType：PLAN_PURCHASE / PLAN_DEDUCT / BALANCE_FEE / DEPOSIT / DEPOSIT_REFUND / OVERDUE_FEE */
    private String paymentType;

    private Integer amountFen;

    private String channel;

    private String tradeNo;

    /** 1 成功 / 2 失败 */
    private Integer status;

    private String remark;

    private Long createTime;
}
