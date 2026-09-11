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
}
