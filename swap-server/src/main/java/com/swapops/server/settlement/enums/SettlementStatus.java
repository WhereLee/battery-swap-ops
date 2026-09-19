package com.swapops.server.settlement.enums;

/**
 * 结算单状态机（S7 WP-B，db/12）：GENERATED→CONFIRMED→PAID，只前向，PAID 不可变
 * （迟到流水／退款冲正／欠费补缴一律进下一期，见 {@code SettlementService#generate}）。
 *
 * <p>为什么补这个枚举：批次29 之前状态码只写在 {@code SettlementStatementEntity.status} 的行注释里
 * （1/2/3 字面量散落在 service、SQL、前端标签三处）。能力位（{@code ActionsSupport.settlement}）
 * 与前端状态标签都需要"状态集合"这个事实源，字面量复制迟早漂移；补枚举后
 * {@code ActionsSupport} 用 switch 覆盖全部枚举值——<b>新增状态时编译器直接报错</b>，
 * 与 {@code WorkOrderStatus} 的纪律一致。
 */
public enum SettlementStatus {

    /** 已生成：等待运营确认金额。 */
    GENERATED(1),

    /** 已确认：等待打款。 */
    CONFIRMED(2),

    /** 已打款：终态，不可变。 */
    PAID(3);

    private final int code;

    SettlementStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /** 未知/空值抛异常（与 {@code WorkOrderStatus} 同型）；调用方若不希望抛，先自行判空。 */
    public static SettlementStatus fromCode(Integer code) {
        for (SettlementStatus status : values()) {
            if (code != null && status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知结算单状态: " + code);
    }
}
