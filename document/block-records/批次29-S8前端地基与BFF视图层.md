# 批次29 · S8 前端地基与 BFF 视图层（后端侧）

> 日期：2026-09-19 ｜ 阶段：S8（前端管理台与 BFF 视图层）第 1 批 ｜ 方案：`document/plans/S8-前端管理台与BFF视图层-方案.md`
> 结论：**本地全量验证绿（473/473）+ 剧本 `_c32` 54 PASS / 0 FAIL / 1 SKIP + `mvn clean verify -DskipITs` BUILD SUCCESS**

## 一、背景与目标

用户定调：前端要做（中小厂看重"后端能独立交付可视成果"的全栈能力）。开工前先回答用户提出的架构问题——
"一个业务功能要按顺序调多个后端接口，是不是要在后端做业务流/编排？"

**结论（写进方案 §3）：不引入编排引擎**。按"链路断掉后业务算失败还是算进行到一半"分四类处置：
系统内一次动作 → 后端单入口（`/user/order` 现状正确）；跨角色跨时刻流程 → 必须保持多接口（工单五步各自鉴权+审计）；
现场逐台操作 → 保持逐个 + 前端显示进度；**读侧要拼多接口 → 这才是真缺口，由 BFF 视图层解决**。

## 二、交付

### 29a 安全与契约地基
| 项 | 内容 |
|---|---|
| `GET /admin/auth/me` | 登录原先只回 `{token}`，前端刷新后无法恢复权限上下文 → 现回 `role/codes/dataScope/scopeStationIds/bootstrap/dataScoped` |
| `common/action/ActionsSupport` | 能力位纯函数：工单 6 态、建议单 5 态、调拨 5 态、告警 handled → `allowedActions`；工单用**镜像枚举 + switch 全覆盖**，新增状态未登记即编译失败 |
| `asset/service/DeviceOwnershipService` | `deviceNo → stationId` 解析（柜号 / 仓级号截断 / 电池三级链），解不出返回 null 不猜 |
| `db/17-work-order-scope.sql` | `work_order.station_id` + 索引 + 两步回填（柜号维度、电池维度），幂等；本地实测 15 张工单回填 14 张、1 张系统级留 NULL |
| 工单数据权限 | `page()` 加 `applyStation`；`require()` 加 `requireStationAccess`（所有动作与详情的唯一取数入口）；controller 挂 `@DataFilter` |
| `PermissionCodeContractTest` | 前端声明的权限码必须 ⊆ `AdminRole.ALL_CODES`；`swap-web` 未落地时按 `Assumptions` 跳过（本批 Skipped=1 即此项） |

### 29b BFF 视图层（`com.swapops.server.web.view`）
- `AdminViews`：14 个 record VO（看板/告警/建议单/工单/工单详情/柜/仓/指令/订单摘要/订单详情/支付/退款/告警摘要）；
  **柜 VO 有意不含 `secret`**，订单 VO 的 `refundableFen` 取服务端资金口径（不由前端按流水推算）。
- `AdminObservationViewService`：看板 Map→VO（缺键按 0 兜底不抛）、告警页（批量取工单映射防 N+1；`create-work-order`
  仅在"未处理且尚无工单"时给出）、建议单页（解析 `params_json` 带出来源告警类型，脏 JSON 容忍）。
- `AdminWorkflowViewService`：工单页/详情（含流转日志与来源告警）、柜详情（档案+仓与电池+未处理告警+进行中订单+最近指令，
  嵌套列表各限 20 条）、订单详情（时间线+支付/退款流水+可退金额）；柜与订单在取数处即 `requireStationAccess`。
- `AdminViewController`：7 个 GET，`@PreAuthorize` 用既有权限码，`@Tag/@Operation` 让 OpenAPI 出真 schema。

## 三、关键决策与如实申报

1. **BFF 不独立成进程**（企业标准形态是独立 BFF 服务）：当前只有一个消费方、单体已按域分包，
   独立进程要付跨服务鉴权+多一份 CI/部署件+一次网络跳转，收益要等"多端差异化聚合"出现才兑现。已在方案 §4.1 申报，用户授权后定案。
2. **告警页/建议单页有意不挂 `@DataFilter`**：`alarm`、`agent_action` 两表无站点归属列（系统级/跨站级资源，全局口径），
   挂了只会让自检切面误报"空转"。→ 与工单口径不一致是**已知边界**，记录在方案 §4.8。
3. **纠正上一轮判断**：此前称"工单/结算/对账/调拨/告警列表未挂 `@DataFilter` = 越权面"，实测 `transfer_task`/`recon_diff`/
   `channel_bill` 均无站点归属列，属全局口径资源；**只有工单确有归属却未过滤**，本批已修（方案 §2 纠正段）。
4. **存量接口不做一次性 VO 化**：76 个管理端接口的回归风险大于收益，按"页面用到才改"推进（方案 §4.2、§7）。

## 四、验证证据

| 项 | 结果 |
|---|---|
| 全量单测 | **473/473**（contract 4 + server 409 + sim 31 + agent 29），Skipped 1（前端契约，设计如此） |
| `mvn clean verify -DskipITs` | **BUILD SUCCESS**；jacoco 四模块全部达标；SpotBugs 四模块通过 |
| 行覆盖率 | contract 77.2% / **server 71.5%（5400/7552，较上批 70.0% 上升）** / sim 61.7% / agent 86.2% |
| 剧本 `_c32_admin_view.ps1` | **54 PASS / 0 FAIL / 1 SKIP** → `GATE-ADMIN-VIEW PASS`（`scripts/verify/batch29/_c32_out.txt`） |
| 剧本覆盖 | me 与 401 语义、看板 VO、告警能力位（含"已有工单不再给建单"）、建议单来源告警解析、**工单五步动作链逐态断言**、柜详情聚合且**响应原文不含 `secret`**、订单可退金额、受限身份本域可读/越域 403/**无归属工单 403（fail-closed）**、迁移重复执行幂等 |
| SKIP 说明 | H12"外域工单详情 403"实机数据无外域工单，由单测 `WorkOrderScopeTest` 覆盖（剧本内已注明） |
| OpenAPI | `document/api/openapi.json` 重新导出：**101 → 109 paths**（7 个 view + `auth/me`），82,149 字节 |

## 五、过程中的问题（含工具坑）

1. **Write 工具报 "unknown" 后留下残留桩**：`AdminViewController.java` 实际写入成功，但文件尾被追加了重复的
   `package` 声明与空类（103-107 行）→ 编译报"需要 class、interface、enum 或 record"。
   **教训：报 unknown 后不能只 `Test-Path` 判存在，要看文件尾部/直接编译。**
2. **health 路径**：正确为 `/api/actuator/health`（`context-path: /api`）；`/api/health` 返回 500、`/actuator/health` 404，
   首次探测 45 轮全失败误判"启动失败"，实际平台正常。
3. **`.NET` 静态方法用进程 cwd**：`[System.IO.File]::Open("server.err.log")` 在 PS `cd` 之后仍按进程 cwd 解析 → 文件未找到；
   改绝对路径 + `New-Object -TypeName ... -ArgumentList`（PS 5.1 位置参数构造失败）。
4. **MQ 噪音**：broker 未启动时 `OutboxRelayTask` 与 `DeviceEventMqConsumer` 持续 WARN/ERROR（重试与 consumer 重建），
   不影响管理端路径，剧本照常通过；日志中文在控制台回显为乱码属回显编码，文件本身正常。
5. **单模块构建缺同仓产物**：`mvn -pl swap-server test` 因本地仓库无 `swap-contract:1.0.0` 失败 → 加 `-am`
   并配 `-Dsurefire.failIfNoSpecifiedTests=false`。

## 六、边界与后续

- 本批只做后端侧；**前端工程 `swap-web` 在批次30**（脚手架/登录/路由守卫/`v-access`/看板页/告警与建议单页 + CI frontend job），
  届时 `PermissionCodeContractTest` 的前端侧断言自动生效。
- 批次31：工单/柜/订单/结算业务页 + nginx 部署（dist 静态 + `/api` 反代，同源故后端不开 CORS）+ 云上验收。
- 结算视图（`/admin/view/settlement/{id}`）留到批次31 与结算页同期做，避免在无页面消费方时先造接口。
- 云侧未部署本批改动（`db/17` 未在云上执行）——按块循环纪律，云上迁移与部署待本阶段收口时一并做并记录。
