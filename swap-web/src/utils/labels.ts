/**
 * Enum -> display label maps.
 *
 * Why this file exists instead of inline ternaries: a screenshot review of the console
 * caught the work-order list printing the raw code `USER_REPORT` in the 来源 column.
 * The cause was an inline label map that guessed the wrong constant (`REPORT`), and a
 * wrong guess fails silently - it renders the code and nobody notices. Keeping the maps
 * in one place, next to the values the server actually writes, gives one file to update
 * and one file to grep when an enum changes.
 *
 * Values below are the literal constants written by the backend:
 * - swap_order.order_type  : TAKE / SWAP / RETURN      (OrderService, SwapOrderServiceImpl)
 * - work_order.source      : ALARM / USER_REPORT       (WorkOrderService)
 * - work_order.severity    : HIGH / MEDIUM / LOW       (WorkOrderService)
 * `?? value` is deliberate: an unmapped value must still render something truthful
 * rather than an empty cell, so a new enum value degrades to the code, not to blank.
 */

const ORDER_TYPE: Record<string, string> = {
  TAKE: "取电",
  SWAP: "换电",
  RETURN: "归还",
};

const WORK_ORDER_SOURCE: Record<string, string> = {
  ALARM: "告警派生",
  USER_REPORT: "用户报障",
  MANUAL: "人工建单",
};

const SEVERITY: Record<string, string> = {
  HIGH: "高",
  MEDIUM: "中",
  LOW: "低",
};

export function orderTypeLabel(value: string | null | undefined): string {
  if (!value) {
    return "-";
  }
  return ORDER_TYPE[value] ?? value;
}

export function workOrderSourceLabel(value: string | null | undefined): string {
  if (!value) {
    return "-";
  }
  return WORK_ORDER_SOURCE[value] ?? value;
}

export function severityLabel(value: string | null | undefined): string {
  if (!value) {
    return "-";
  }
  return SEVERITY[value] ?? value;
}
