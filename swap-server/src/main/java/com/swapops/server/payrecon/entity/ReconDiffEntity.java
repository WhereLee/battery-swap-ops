package com.swapops.server.payrecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 渠道对账差异单（S7 WP-C，db/13）：唯一键 (bill_date, channel, trade_no, diff_type)；
 * OPEN 差异在重导后按重算结果重建；HANDLED/IGNORED 保留为处置留痕。
 */
@Data
@TableName("recon_diff")
public class ReconDiffEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String billDate;

    private String channel;

    private String tradeNo;

    /** ReconDiffType：CHANNEL_ONLY / PLATFORM_ONLY / AMOUNT_MISMATCH / STATUS_MISMATCH */
    private String diffType;

    private String detail;

    /** ReconDiffStatus：OPEN / HANDLED / IGNORED */
    private String status;

    private String handledBy;

    private Long handledTime;

    private String remark;

    private Long createTime;
}
