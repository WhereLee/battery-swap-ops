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

## 5. 数据范围（P1-8，按站点隔离）

- 模型：角色（能干什么）× 数据范围（能看谁的数据）正交；`admin_user.data_scope` = ALL（缺省）/ STATION，
  STATION 时 `scope_station_nos` 逗号分隔站点编号（创建时校验存在，防"配了不存在的站→静默无数据"）；
- 身份化：登录期解析为站点 id 集入 `AdminContext.Principal`；**fail-closed**——解析异常/空配置=空集=不见任何数据；
- 注入点：`DataScopeSupport.applyStation/applyIds`（类型安全的 `.in()`/`.eq()`，不拼 SQL）；
  两级解析 `站点→柜 id 集→仓 id 集`覆盖 cell/battery；空集→`eq(列,-1)` 恒假；
- 资源级：详情（柜实况/电池健康/订单详情）+ 状态运维 + 登记 CRUD 越域一律 403；
  无站点归属操作（新建站点/在途电池登记）对受限身份 403；
- 看板：受限身份**绕全局缓存直算**本域口径（不读也不污染全局聚合）；
- 自检：`@DataFilter` 标记 8 个查询端点 + `DataFilterAspect` 旁路告警（受限身份进入标注端点却从未 apply 时记 warn），
  防未来新增查询漏接；实测 0 告警；
- 覆盖边界：资产域（站/柜/仓/电池）+ 订单 + 看板；告警/工单/资金/结算未按站隔离（无站点维度或总部角色使用）。

## 6. 证据

- 单测：`AdminRoleTest`（矩阵边界）、`AdminSecretsTest`、`AdminAuthServiceTest`、`AdminAuthFilterTest`、
  `AdminLogAspectTest`（脱敏/失败留痕）、`AdminEndpointGuardTest`（覆盖性守卫）、`DataScopeSupportTest`（数据范围三态）、
  `AdminAccountServiceTest`（范围字段校验）；
- 实机剧本：`scripts/verify/batch10/_c17_rbac.ps1`——18/18 PASS（未认证 401 / 三角色矩阵 403·200 /
  审计真实身份 / 登出吊销 / break-glass 存活）；`scripts/verify/batch22/_c27_data_scope.ps1`——24/24 PASS
  （域内可见/域外 403/写入 403/无副作用）；
- 回归：`_c16`（旧脚本零改动，静态 token 走 break-glass）16/16 PASS；`_c24`（指标）9/9 PASS（批22 同轮）。

## 7. 取舍与边界

- 动态菜单/按钮级权限未做（无前端）；管理员令牌不可被 SUPER 强制吊销（可停用账号即时生效）；
- 登录失败仅审计+限流，无账号锁定（撞库防护依赖 IP 限流；可后置 LoginAttemptGuard 同壳子）；
- 数据范围是登录期快照（会话期内改配置需重新登录生效）；未做"创建者"行级维度与可视化配置界面。
