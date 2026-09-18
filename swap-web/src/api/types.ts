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
