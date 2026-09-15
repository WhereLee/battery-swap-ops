# 批次10 —— S7 WP-0/WP-A：资金缺陷修复 + 管理端身份/权限/审计（2026-09-15）

## WP-0 既有资金缺陷修复（先行）

- 押金二次退款：`RefundService.REFUNDABLE_TYPES` 移除 DEPOSIT（押金只走退租 RETURN 的 refundDeposit）；
- `AdminRefundController`：COMPLETED 拒绝人工退款（指向冲正流程）、可退金额 0 拒绝；
- 新增 `AdminRefundControllerTest`（三态）+ 口径修正测试；修复档 `document/fixes/deposit-double-refund.md`。

## WP-A 管理端身份/权限/审计（RBAC 移植版）

- 参照物：壳子（inteink-faster）的 SecurityConfig(@EnableMethodSecurity) + AuthTokenFilter +
  SysLogAspect + BCrypt 模式；差异=不建 sys_menu，角色→权限码静态化（API-only 无前端菜单需求）。
- 交付：
  - `db/11-admin-rbac.sql`（幂等）：admin_user / admin_op_log / refund_record 加操作人列；
  - 安全链：`AdminSecurityConfig`（securityMatcher=/admin/**，设备/用户/dev/actuator 零影响）+
    `AdminAuthFilter`（会话 token / break-glass 静态 token；**旧 AdminTokenFilter 退役**）；
  - 4 角色 × 38 权限码（`AdminRole`），`@PreAuthorize` 挂满 18 个管理控制器 / 61 个端点；
  - 审计：`@AdminLog` + 切面（脱敏/截断/失败留痕）+ 登录直写审计 + `/admin/account/op-log` 查询；
  - 身份接通：告警 handler / 工单 operator / 调拨 operator / 建议单 confirmer·proposer / 退款操作人；
  - 账号管理：`/admin/account`（创建/列表/启停）+ 登录会话 `/admin/auth`（TTL 12h，登出吊销）；
  - 引导：dev 种子管理员（`SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD` env；云上入 swap.env）。
- 测试：新增 7 个测试类（含 **AdminEndpointGuardTest 端点注解覆盖守卫**：反射断言全端点
  @PreAuthorize + 写端点 @AdminLog + 权限码合法）；单测 327/327。
- 剧本：`scripts/verify/batch10/_c17_rbac.ps1` **18/18 PASS**（未认证 401 / 角色矩阵 / 审计真实身份 /
  登出吊销 / break-glass）；`_c16` 回归 16/16（旧脚本零改动）。

## 实机踩坑（已修入剧本纪律）

1. **.ps1 必须全英文**：首版 `_c17` 用了中文检查名——PS 5.1 按 ANSI 读无 BOM UTF-8，中文字节吞引号
   导致解析损坏（检查名里混入条件表达式、部分 Check 未执行）。改写英文后立即 18/18。
   （呼应 pitfalls/ps-utf8-bom-and-mojibake.md，本次是"吞引号破坏语法"的更严重形态。）
2. Swagger 无关项：`/admin/dashboard` 404 —— 看板端点是 `/admin/dashboard/overview`（剧本路径笔误）。
3. MP lambda 缓存：`AdminAuthServiceTest` 需 `TableInfoHelper.initTableInfo`（老坑再现）。

## 证据与提交

- `mvn clean verify`（含覆盖率门槛）+ 随机顺序全绿；CI 见提交 run。
- 文档：`knowledge/rbac-and-audit.md`（设计/矩阵/用法/边界）；runbook 增"管理端登录"小节。

## 遗留

- 数据权限（DataFilter 模式）与登录锁定（LoginAttemptGuard）后置声明；
- 云端应用 db/11 + 重部署（本批次随后执行）。
