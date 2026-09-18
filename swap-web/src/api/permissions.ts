/**
 * Permission codes used by this frontend.
 *
 * These strings are the SAME codes the backend enforces with @PreAuthorize
 * (com.swapops.server.admin.enums.AdminRole). They are declared here as literals on
 * purpose: the backend test PermissionCodeContractTest reads this file and fails the
 * build when a code used by the UI does not exist in AdminRole.ALL_CODES. That turns
 * "button never shows up" / "click then 403" drift into a CI failure.
 *
 * Route-level: router meta.codes. Button-level: v-access directive.
 */
export const CODES = {
  alarmRead: "admin:alarm:read",
  alarmHandle: "admin:alarm:handle",
  workOrderRead: "admin:work-order:read",
  workOrderManage: "admin:work-order:manage",
  assetRead: "admin:asset:read",
  assetManage: "admin:asset:manage",
  transferRead: "admin:transfer:read",
  transferManage: "admin:transfer:manage",
  chargePolicyRead: "admin:charge-policy:read",
  chargePolicyManage: "admin:charge-policy:manage",
  planRead: "admin:plan:read",
  planManage: "admin:plan:manage",
  orderRead: "admin:order:read",
  refundCreate: "admin:refund:create",
  settlementRead: "admin:settlement:read",
  settlementManage: "admin:settlement:manage",
  reconRead: "admin:recon:read",
  reconImport: "admin:recon:import",
  reconHandle: "admin:recon:handle",
  userRead: "admin:user:read",
  userManage: "admin:user:manage",
  arrearsRead: "admin:arrears:read",
  arrearsWaive: "admin:arrears:waive",
  couponRead: "admin:coupon:read",
  couponManage: "admin:coupon:manage",
  reportRead: "admin:report:read",
  dashboardRead: "admin:dashboard:read",
  cacheRead: "admin:cache:read",
  ratelimitRead: "admin:ratelimit:read",
  reconcileRead: "admin:reconcile:read",
  reconcileRun: "admin:reconcile:run",
  opsRun: "admin:ops:run",
  suggestionRead: "admin:suggestion:read",
  suggestionManage: "admin:suggestion:manage",
  agentMgmtRead: "admin:agent-mgmt:read",
  agentMgmtManage: "admin:agent-mgmt:manage",
  adminManage: "admin:admin:manage",
} as const;

export type PermissionCode = (typeof CODES)[keyof typeof CODES];
