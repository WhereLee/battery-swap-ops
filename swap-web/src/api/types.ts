/**
 * View-object types, mirroring com.swapops.server.web.view.AdminViews 1:1.
 *
 * Kept in-repo on purpose: `npm run gen:api` regenerates src/api/schema.d.ts from
 * document/api/openapi.json, but that file is a build artifact (gitignored). These
 * hand-written types are what the compiler checks against, and they are the contract
 * the pages rely on. If the backend VO changes, gen:api output and this file diverge
 * and type-check fails at the call site.
 */

export interface MeVO {
  adminId: number | null;
  username: string;
  role: string;
  bootstrap: boolean;
  dataScope: string;
  dataScoped: boolean;
  scopeStationIds: number[];
  codes: string[];
}

export interface DashboardVO {
  generatedAt: number;
  totalBatteries: number;
  fullBatteries: number;
  fullBatteryRate: number;
  totalStations: number;
  availableStations: number;
  stationAvailabilityRate: number;
  completedToday: number;
  turnoverRate: number;
  unavailableStations: string[];
  dataScoped: boolean;
}

export interface AlarmBriefVO {
  id: number;
  alarmType: string;
  deviceType: string;
  deviceNo: string;
  content: string;
  handled: number;
  createTime: number;
}

export interface AlarmItemVO extends AlarmBriefVO {
  handler: number | null;
  handledTime: number | null;
  workOrderId: number | null;
  allowedActions: string[];
}

export interface SuggestionVO {
  id: number;
  actionNo: string;
  actionType: string;
  idemKey: string;
  reason: string;
  status: number;
  proposer: string;
  confirmer: string | null;
  confirmTime: number | null;
  resultJson: string | null;
  errorMsg: string | null;
  createTime: number;
  alarmId: number | null;
  alarmType: string | null;
  allowedActions: string[];
}

export interface WorkOrderVO {
  id: number;
  woNo: string;
  alarmId: number | null;
  source: string;
  reporterUserId: number | null;
  description: string | null;
  deviceType: string;
  deviceNo: string;
  stationId: number | null;
  title: string;
  severity: string;
  status: number;
  handlerId: number | null;
  slaDeadline: number | null;
  slaBreached: number;
  verifyTime: number | null;
  closeTime: number | null;
  remark: string | null;
  createTime: number;
  updateTime: number;
  allowedActions: string[];
}

export interface WorkOrderLogVO {
  action: string;
  fromStatus: number | null;
  toStatus: number | null;
  operator: string;
  remark: string | null;
  createTime: number;
}

export interface WorkOrderDetailVO {
  order: WorkOrderVO;
  logs: WorkOrderLogVO[];
  alarm: AlarmBriefVO | null;
}

export interface CabinetVO {
  id: number;
  cabinetNo: string;
  stationId: number;
  cellCount: number;
  status: number;
  lastBootId: string | null;
  lastEventSeq: number | null;
  lastHeartbeatTime: number | null;
  heartbeatAgeMs: number | null;
}

export interface CellVO {
  cellNo: number;
  status: number;
  batteryNo: string | null;
  soc: number | null;
  lockOrderId: number | null;
}

export interface CommandVO {
  commandAction: string;
  commandSeq: number;
  commandStatus: number;
  retryCount: number;
  traceId: string | null;
  createTime: number;
}

export interface OrderBriefVO {
  orderNo: string;
  orderType: string;
  status: number;
  feeFen: number | null;
  createTime: number;
}

export interface CabinetDetailVO {
  cabinet: CabinetVO;
  cells: CellVO[];
  openAlarms: AlarmBriefVO[];
  activeOrders: OrderBriefVO[];
  recentCommands: CommandVO[];
}

export interface PaymentVO {
  tradeNo: string | null;
  amountFen: number;
  paymentType: string;
  status: number;
  createTime: number;
}

export interface RefundVO {
  refundNo: string;
  amountFen: number;
  reason: string;
  status: string;
  createTime: number;
}

export interface OrderDetailVO {
  orderNo: string;
  orderType: string;
  userId: number;
  stationId: number | null;
  cabinetNo: string | null;
  cellNo: number | null;
  takeBatteryNo: string | null;
  returnBatteryNo: string | null;
  status: number;
  statusDesc: string;
  feeFen: number | null;
  discountFen: number | null;
  payType: string | null;
  preemptExpireTime: number | null;
  createTime: number;
  openTime: number | null;
  takeTime: number | null;
  returnTime: number | null;
  completeTime: number | null;
  cancelTime: number | null;
  closeReason: string | null;
  payments: PaymentVO[];
  refunds: RefundVO[];
  refundableFen: number;
  /**
   * Money-path capability bits computed server-side: "refund" for an in-flight order,
   * "reversal" for a completed one (its money is already settled and split, so the only
   * legal path is a reversing ledger entry). The UI must not pick between the two itself.
   */
  allowedActions: string[];
}

/** Row shape of GET /admin/view/order — the list never carries internal columns (idemKey etc.). */
export interface OrderListItemVO {
  orderNo: string;
  orderType: string;
  userId: number;
  stationId: number | null;
  cabinetNo: string | null;
  status: number;
  statusDesc: string;
  feeFen: number | null;
  discountFen: number | null;
  payType: string | null;
  createTime: number;
  completeTime: number | null;
  refundableFen: number;
  allowedActions: string[];
}

export interface SettlementVO {
  id: number;
  statementNo: string;
  agentId: number | null;
  agentName: string | null;
  periodStart: number;
  periodEnd: number;
  orderCount: number | null;
  baseAmountFen: number | null;
  agentAmountFen: number | null;
  platformAmountFen: number | null;
  subsidyFen: number | null;
  status: number;
  generatedBy: string | null;
  confirmedBy: string | null;
  paidBy: string | null;
  generatedTime: number | null;
  confirmedTime: number | null;
  paidTime: number | null;
  remark: string | null;
  createTime: number | null;
  updateTime: number | null;
  allowedActions: string[];
}

/** One append-only split-ledger line; REFUND_REVERSAL lines are negative. */
export interface SettlementLineVO {
  id: number;
  orderNo: string;
  stationId: number | null;
  eventType: string;
  baseType: string;
  baseAmountFen: number | null;
  agentShareFen: number | null;
  platformShareFen: number | null;
  subsidyFen: number | null;
  createTime: number;
}

export interface SettlementDetailVO {
  statement: SettlementVO;
  lines: SettlementLineVO[];
}

/** Human-readable labels shared by pages; the codes themselves come from the backend. */
export const WORK_ORDER_STATUS: Record<number, string> = {
  1: "待分诊",
  2: "已分诊",
  3: "已派单",
  4: "处置中",
  5: "已验收",
  6: "已关闭",
};

export const SUGGESTION_STATUS: Record<number, string> = {
  1: "待确认",
  2: "已执行",
  3: "已驳回",
  4: "执行失败",
  5: "执行中",
};

export const CABINET_STATUS: Record<number, string> = {
  1: "在线",
  2: "满载",
  3: "故障",
  4: "维护",
  5: "停用",
};

export const WORK_ORDER_ACTION_LABEL: Record<string, string> = {
  triage: "分诊",
  assign: "派单",
  start: "开始处置",
  verify: "验收",
  close: "关闭",
};

/** OrderStatus codes (swap-contract OrderStatus). */
export const ORDER_STATUS: Record<number, string> = {
  1: "待开仓",
  2: "已开仓",
  3: "已取待还",
  4: "逾期占用",
  5: "已完成",
  6: "已取消",
  7: "超时关闭",
  8: "异常",
};

/** SettlementStatus codes (settlement/enums/SettlementStatus). */
export const SETTLEMENT_STATUS: Record<number, string> = {
  1: "已生成",
  2: "已确认",
  3: "已打款",
};

export const SETTLEMENT_ACTION_LABEL: Record<string, string> = {
  confirm: "确认结算单",
  paid: "标记已打款",
};

/** Order money-path action codes returned in OrderDetailVO.allowedActions. */
export const ORDER_ACTION_LABEL: Record<string, string> = {
  refund: "人工退款",
  reversal: "冲正退款",
};

/** CellStatus codes (swap-contract CellStatus). */
export const CELL_STATUS: Record<number, string> = {
  1: "空闲",
  2: "占用",
  3: "故障",
  4: "停用",
};

/** CommandStatus codes (swap-contract CommandStatus) — read-only command ledger. */
export const COMMAND_STATUS: Record<number, string> = {
  1: "待到位",
  2: "已销账",
  3: "下发失败",
  4: "重试超限",
  5: "已被取代",
  6: "设备故障中断",
};

/** Split-ledger event types (settlement/service/SettlementService). */
export const SETTLEMENT_EVENT_TYPE: Record<string, string> = {
  ORDER: "订单分账",
  REFUND_REVERSAL: "退款冲正",
  ARREARS_SETTLE: "欠费补缴",
};

/** Split base types: how the split base was derived (cash / plan / reversal...). */
export const SETTLEMENT_BASE_TYPE: Record<string, string> = {
  CASH: "实收+券抵扣",
  PLAN_TIMES: "次卡折算",
  PLAN_MONTHLY: "月卡（不计次）",
  REVERSAL: "冲正",
  ARREARS: "补缴",
};

/**
 * refund_record.reason values actually written by the backend (three call sites:
 * AdminRefundController#refund, AdminRefundController#reversal, RefundCompensationTask).
 * Deposit returns do NOT go through refund_record — they are direct wallet credits.
 */
export const REFUND_REASON: Record<string, string> = {
  ADMIN_MANUAL: "人工退款",
  ADMIN_REVERSAL: "冲正退款",
  ORDER_EXCEPTION: "订单异常自动退",
};

/**
 * Agent action whitelist (com.swapops.server.agent.enums.AgentActionType). Four values,
 * deliberately closed — money-related actions are excluded from the whitelist by design.
 * Unknown values fall back to the raw enum name rather than being guessed at.
 */
export const AGENT_ACTION_TYPE: Record<string, string> = {
  CREATE_WORK_ORDER_FROM_ALARM: "由告警建工单",
  ASSIGN_WORK_ORDER: "工单派单",
  RUN_RECONCILE: "触发日终对账",
  APPLY_CHARGE_POLICY: "下发充电策略",
};

/**
 * Alarm types stay as raw enum codes on purpose: `content` already carries the
 * human-readable Chinese description, and ops staff search/grep by the type code.
 * Translating it would add a second mapping table that can drift from AlarmType.
 */
export function agentActionLabel(actionType: string | null | undefined): string {
  if (!actionType) {
    return "-";
  }
  return AGENT_ACTION_TYPE[actionType] ?? actionType;
}
