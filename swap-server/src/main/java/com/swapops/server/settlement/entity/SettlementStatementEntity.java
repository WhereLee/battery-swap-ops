package com.swapops.server.settlement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 结算单（S7 WP-B，db/12）：顺序批（未挂单流水）；GENERATED→CONFIRMED→PAID，PAID 不可变。
 */
@Data
@TableName("settlement_statement")
public class SettlementStatementEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String statementNo;

    private Long agentId;

    private Long periodStart;

    private Long periodEnd;

    private Integer orderCount;

    private Integer baseAmountFen;

    private Integer agentAmountFen;

    private Integer platformAmountFen;

    private Integer subsidyFen;

    /** 1 GENERATED / 2 CONFIRMED / 3 PAID */
    private Integer status;

    private String generatedBy;

    private String confirmedBy;

    private String paidBy;

    private Long generatedTime;

    private Long confirmedTime;

    private Long paidTime;

    private String remark;

    private Long createTime;

    private Long updateTime;
}
