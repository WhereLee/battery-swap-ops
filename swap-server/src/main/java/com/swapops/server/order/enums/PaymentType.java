package com.swapops.server.order.enums;

/**
 * 支付记录类型（每条 = 一次资金动作；与 order_id 组合唯一，天然幂等）。
 */
public enum PaymentType {
    /** 购买套餐 */
    PLAN_PURCHASE,
    /** 套餐扣次（金额 0） */
    PLAN_DEDUCT,
    /** 余额单次扣费 */
    BALANCE_FEE,
    /** 押金缴纳 */
    DEPOSIT,
    /** 押金退还 */
    DEPOSIT_REFUND,
    /** 超时费 */
    OVERDUE_FEE,
    /** 充值入账（S3.4：外部资金入口，order_id 为空） */
    RECHARGE,
    /** 退款出账（S3.4：异常补偿/人工，与收费类型互斥） */
    REFUND
}
