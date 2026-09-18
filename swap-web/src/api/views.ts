import { get, post, type PageResult } from "./http";
import type {
  AlarmItemVO,
  CabinetDetailVO,
  DashboardVO,
  MeVO,
  OrderDetailVO,
  SuggestionVO,
  WorkOrderDetailVO,
  WorkOrderVO,
} from "./types";

// ---------- identity ----------

export function fetchMe(): Promise<MeVO> {
  return get<MeVO>("/admin/auth/me");
}

export function login(username: string, password: string): Promise<{ token: string }> {
  return post<{ token: string }>("/admin/auth/login", { username, password });
}

export function logout(): Promise<void> {
  return post<void>("/admin/auth/logout");
}

// ---------- BFF views (one request per page) ----------

export function fetchDashboard(): Promise<DashboardVO> {
  return get<DashboardVO>("/admin/view/dashboard");
}

export function fetchAlarmPage(params: { page?: number; limit?: number; handled?: number }): Promise<PageResult<AlarmItemVO>> {
  return get<PageResult<AlarmItemVO>>("/admin/view/alarm", params);
}

export function fetchSuggestionPage(params: { page?: number; limit?: number; status?: number }): Promise<PageResult<SuggestionVO>> {
  return get<PageResult<SuggestionVO>>("/admin/view/agent-action", params);
}

export function fetchWorkOrderPage(params: { page?: number; limit?: number; status?: number }): Promise<PageResult<WorkOrderVO>> {
  return get<PageResult<WorkOrderVO>>("/admin/view/work-order", params);
}

export function fetchWorkOrderDetail(id: number): Promise<WorkOrderDetailVO> {
  return get<WorkOrderDetailVO>(`/admin/view/work-order/${id}`);
}

export function fetchCabinetDetail(cabinetNo: string): Promise<CabinetDetailVO> {
  return get<CabinetDetailVO>(`/admin/view/cabinet/${encodeURIComponent(cabinetNo)}`);
}

export function fetchOrderDetail(orderNo: string): Promise<OrderDetailVO> {
  return get<OrderDetailVO>(`/admin/view/order/${encodeURIComponent(orderNo)}`);
}

// ---------- actions (domain endpoints; the view layer is read-only) ----------

export function handleAlarm(id: number): Promise<unknown> {
  return post<unknown>(`/admin/alarm/${id}/handle`);
}

export function createWorkOrderFromAlarm(alarmId: number, severity?: string): Promise<WorkOrderVO> {
  return post<WorkOrderVO>(`/admin/work-order/from-alarm/${alarmId}`, undefined, severity ? { severity } : undefined);
}

export function confirmSuggestion(id: number): Promise<unknown> {
  return post<unknown>(`/admin/agent-action/${id}/confirm`);
}

export function rejectSuggestion(id: number, remark?: string): Promise<unknown> {
  return post<unknown>(`/admin/agent-action/${id}/reject`, undefined, remark ? { remark } : undefined);
}

/**
 * Work order state machine step. The allowed action names come from the backend
 * (`allowedActions`), so the UI never decides which transitions exist.
 */
export function workOrderStep(
  id: number,
  action: "triage" | "assign" | "start" | "verify" | "close",
  params?: { severity?: string; handlerId?: number; remark?: string },
): Promise<WorkOrderVO> {
  return post<WorkOrderVO>(`/admin/work-order/${id}/${action}`, undefined, params);
}
