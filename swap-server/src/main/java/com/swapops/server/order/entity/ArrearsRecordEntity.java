package com.swapops.server.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 欠费单（S7 WP-D，db/14）：超时费不足额落单（同订单唯一）；补缴/减免结清。
 */
@Data
@TableName("arrears_record")
public class ArrearsRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long orderId;

    private String orderNo;

    /** 欠费来源（S7 韧性补丁）：BALANCE_FEE / DEPOSIT / OVERDUE_FEE（多个以 + 连接） */
    private String reason;

    /** 欠费应收（分） */
    private Integer amountFen;

    /** 已结清（补缴+减免） */
    private Integer settledFen;

    /** 1 OPEN / 2 SETTLED */
    private Integer status;

    private Long createTime;

    private Long settleTime;
}
