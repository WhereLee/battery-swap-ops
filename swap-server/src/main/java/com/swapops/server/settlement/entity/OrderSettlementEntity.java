package com.swapops.server.settlement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 分账流水（S7 WP-B，db/12）：append-only；退款冲正=负向行；event_key 唯一（幂等闸）。
 */
@Data
@TableName("order_settlement")
public class OrderSettlementEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键：orderNo:ORDER / orderNo:REV:refundNo / orderNo:ARR:arrearsId */
    private String eventKey;

    private Long orderId;

    private String orderNo;

    private Long stationId;

    /** NULL=直营（流水仍落，不参与结算单） */
    private Long agentId;

    /** ORDER / REFUND_REVERSAL / ARREARS_SETTLE */
    private String eventType;

    /** CASH / PLAN_TIMES / PLAN_MONTHLY / REVERSAL / ARREARS */
    private String baseType;

    /** 分账基数（冲正为负） */
    private Integer baseAmountFen;

    private Integer agentShareFen;

    private Integer platformShareFen;

    /** 券补贴（平台承担） */
    private Integer subsidyFen;

    /** 挂结算单（NULL=未结） */
    private Long statementId;

    private Long createTime;
}
