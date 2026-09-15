# 管理端身份、权限与审计（S7 WP-A）

> 参照壳子（inteink-faster）的 RBAC 设计移植：Spring Security 方法级鉴权 + 权限码 + 操作审计；
> 差异：不建 sys_menu 动态菜单（本项目 API-only，无前端路由需求），角色→权限码为代码内静态映射。

## 1. 身份模型

- `admin_user`（db/11）：账号 / BCrypt 密码 / 真实姓名 / 角色 / 状态 / 最后登录时间；
- 角色（`AdminRole`）四类，权限码矩阵见 S7 方案 §三；权限码命名 `admin:{域}:{动作}`；
- 会话：`POST /admin/auth/login`（IP 限流 5/5s）→ 32hex 安全随机 token → Redis `swap:admin-token:{token}`
  （TTL 12h，可配 `swap.admin.session-ttl-hours`）→ `POST /admin/auth/logout` 吊销；
- **break-glass 静态 token**：`swap.admin.token`（env `SWAP_ADMIN_TOKEN`）保留——映射 SUPER 全权、
  审计 username=`bootstrap`、不随登出吊销、不受登录限流（脚本/运维专用；轮换=换 env + 重启）。

## 2. 鉴权链（影响面收敛）

- `AdminSecurityConfig`：`SecurityFilterChain` 以 `securityMatcher("/admin/**")` 收敛——
  设备（/device）、用户（/user）、联调（/dev、/pay/mock）、actuator 路径**完全不经安全链**（零影响）；
- `AdminAuthFilter`（替换原 `AdminTokenFilter`，旧类已退役）：会话 token 或静态 token → 身份入
  `AdminContext`（ThreadLocal）+ `SecurityContext`（authorities=权限码）；请求末统一清理防串号；
  登录路径豁免；无效 token 直接 401；
- 授权：`@EnableMethodSecurity` + `@PreAuthorize("hasAuthority('admin:xxx:yyy')")` 挂全部端点；
  403 由 `RRExceptionHandler` 的 `AccessDeniedException` 分支输出（防被兜底 500 吞掉）；
- **覆盖性守卫**：`AdminEndpointGuardTest` 反射扫描全部 /admin/** 控制器——映射方法必须带
  `@PreAuthorize` 且权限码在 `AdminRole.ALL_CODES` 内；写方法（POST/PUT/DELETE）必须带 `@AdminLog`；
  新增端点漏挂注解会直接测试失败。

## 3. 操作审计

- `@AdminLog("ACTION")` + `AdminLogAspect`（照壳子 SysLogAspect）：写操作落 `admin_op_log`——
  谁（admin_id/username）/何时/何动作/方法/URI/入参（password、token、secret、sign 字段自动脱敏，
  截断 1000 字）/结果码/错误（截断 255）/耗时/IP；
- 登录/登出：由 `AdminAuthService` 直写（action=LOGIN，密码不落库）；查询类（GET）不落（口径同壳子）；
- 查询：`GET /admin/account/op-log`（权限 `admin:admin:manage`）。

## 4. 身份接通（消除空名/固定名）

| 位置 | 之前 | 现在 |
|---|---|---|
| 告警处理 handler | `UserContext.get()`（admin 态恒 null） | `AdminContext.currentAdminId()` |
| 工单流转 operator | 固定 "admin" | 当前管理员用户名（CREATE/SLA 仍 "system"） |
| 调拨 operator | 客户端传参（默认 admin，可伪造） | 服务端上下文（参数已废除，旧脚本传参被忽略） |
| 建议单 confirmer/proposer | 客户端传参 | 服务端上下文（proposer 不再可伪造） |
| 退款单 operator | 无列 | `refund_record.operator_id/operator_name`（补偿通道为 null） |

## 5. 证据

- 单测：`AdminRoleTest`（矩阵边界）、`AdminSecretsTest`、`AdminAuthServiceTest`、`AdminAuthFilterTest`、
  `AdminLogAspectTest`（脱敏/失败留痕）、`AdminEndpointGuardTest`（覆盖性守卫）；
- 实机剧本：`scripts/verify/batch10/_c17_rbac.ps1`——18/18 PASS（未认证 401 / 三角色矩阵 403·200 /
  审计真实身份 / 登出吊销 / break-glass 存活）；
- 回归：`_c16`（旧脚本零改动，静态 token 走 break-glass）16/16 PASS。

## 6. 取舍与边界

- 数据权限（按站点/网点过滤管理员可见范围）未做——壳子 `DataFilterAspect` 模式留档，后置；
- 动态菜单/按钮级权限未做（无前端）；管理员令牌不可被 SUPER 强制吊销（可停用账号即时生效）；
- 登录失败仅审计+限流，无账号锁定（撞库防护依赖 IP 限流；可后置 LoginAttemptGuard 同壳子）。
