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
    OVERDUE_FEE
}
