package com.swapops.server.settlement.form;

import lombok.Data;

/** 代理表单（S7 WP-B）：编号仅创建时使用。 */
@Data
public class AgentForm {

    private String agentNo;

    private String name;

    private String contact;

    /** 分成比例（万分比 0~10000） */
    private Integer shareBp;

    /** DAILY / WEEKLY / MONTHLY */
    private String settlementCycle;
}
