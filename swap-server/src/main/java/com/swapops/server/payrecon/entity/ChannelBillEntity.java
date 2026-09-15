package com.swapops.server.payrecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 渠道账单明细（S7 WP-C，db/13）：T+1 导入的渠道侧成交记录（唯一键 bill_date+channel+trade_no）。
 */
@Data
@TableName("channel_bill")
public class ChannelBillEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** yyyy-MM-dd（渠道账单日） */
    private String billDate;

    private String channel;

    private String tradeNo;

    private Integer amountFen;

    /** SUCCESS / CLOSED */
    private String status;

    private Long importedAt;
}
