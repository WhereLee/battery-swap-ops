package com.swapops.server.order.form;

import lombok.Data;

/**
 * 下单请求（U1）：类型 + 目标柜/站点（二选一或都不填=自动选可用柜）。
 */
@Data
public class CreateOrderForm {

    /** SWAP / TAKE / RETURN */
    private String type;

    private String cabinetNo;

    private String stationNo;

    /** 可选：用户券 id（S7 WP-D；仅余额计费单可用，套餐单/RETURN 拒绝） */
    private Long couponId;
}
