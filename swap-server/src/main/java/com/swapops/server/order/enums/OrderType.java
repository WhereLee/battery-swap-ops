package com.swapops.server.order.enums;

/**
 * 订单类型（S0.2 §4.1）。
 */
public enum OrderType {
    /** 换电：取满电 + 还亏电（同一仓） */
    SWAP,
    /** 首借：只取满电 */
    TAKE,
    /** 退租：只还电池（分配空仓） */
    RETURN
}
