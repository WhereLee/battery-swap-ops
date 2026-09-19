# S8 前端管理台与 BFF 视图层 — 方案（设计冻结）

> 日期：2026-09-19 ｜ 状态：**批次29（后端地基）+ 批次30（前端骨架）已完成** —— 后端 480/480 单测（Skipped 0）、`mvn clean verify -DskipITs` BUILD SUCCESS、剧本 `_c32` 54 PASS + `_c33` 81 PASS、前端 `type-check`/`build` 双绿 + 浏览器实机联调通过；批次31 待做 ｜ 编号说明：S6 已用于运维 Agent，本阶段为 **S8**
> ❗ 批次30 实机联调抓出一个**存量严重缺陷**（缺分页拦截器 → 13 端点假分页），已修 + 建两层回归网，见批次30 记录 §三与 `pitfalls/mp-pagination-interceptor-missing.md`。
> 前置事实来源：本方案所有"现状"均来自代码/SQL/文档实证（附文件位置），推断项已标注。
> 目标函数（用户定）：**中小厂面试口径下"后端 + 前端"的全栈交付能力自证**，不是产品化前端工程。

---

## 1. 为什么做（问题陈述）

1. 平台已有 101 个 HTTP 端点（`document/api/openapi.json`）但**只能靠脚本/ Swagger 页操作**，S6 运维 Agent 的"建议→人工确认"闭环没有可视入口，演示与验收都要靠剧本。
2. 前端一旦接入，会立即暴露三类后端契约问题（见 §2 调研结论）——**先修契约再上界面**，否则前端会写出一堆"靠猜字段"的代码。

## 2. 调研结论（实证）

| # | 事实 | 出处 | 判定 |
|---|---|---|---|
| 1 | 统一响应 `Result{code,msg,data}`（0 成功）+ 分页 `{list,total,page,limit}` | `common/Result.java`、`common/utils/PageResult.java` | 契约达标，可直接接 |
| 2 | 异常→HTTP 映射完整：业务 400 / 鉴权 403 / 限流 429+`Retry-After` / 校验 400 取首条 / 兜底 500 | `common/RRExceptionHandler.java`（+ 错误处理知识卡） | 前端拦截器有明确依据 |
| 3 | **登录只返回 `{token}`**，无角色、无权限码、无数据范围；**没有 `/admin/auth/me`** | `admin/controller/AdminAuthController.java:41` | **缺口①**（刷新后前端不知道身份） |
| 4 | 权限码 37 个、4 角色（SUPER/OPS/FINANCE/SUPPORT）静态枚举；`AdminRole` 注释明确"**无前端菜单，不做 sys_menu 动态表**" | `admin/enums/AdminRole.java` | 命名已是业界三段式，可直用；菜单方案见 §4.3 |
| 5 | 管理端大量接口 **Entity 直出**（`Result<PageResult<CabinetEntity>>`）或 **`Map<String,Object>` 弱类型**（看板、柜实况） | `AdminCabinetController.java:41,53`、`AdminDashboardController.java:33` | **缺口②**（前端类型靠猜，OpenAPI schema 为空 object） |
| 6 | `@DataFilter` 在本项目**不是 SQL 注入器，而是"防空转自检"切面**；真实过滤在查询构建处（`DataScopeSupport.applyStation/applyIds/requireStationAccess`，fail-closed 空集→恒假条件） | `admin/aspect/DataFilterAspect.java`、`admin/data/DataScopeSupport.java` | 机制清晰，新增查询只需按纪律调用 |
| 7 | `work_order` 只有 `device_no`（无 `station_id`）；`transfer_task`/`recon_diff`/`channel_bill` **无站点归属列** | `db/05`、`db/07`、`db/13` | **纠正**（见下） |
| 8 | 工单状态机 6 态、每步动作有明确 from 态（`cas(order, OPEN, TRIAGED, …)`）；订单 8 态、调拨 5 态、建议单 5 态 | `WorkOrderService.java:151-200`、`WorkOrderStatus`、`OrderStatus`、`TransferStatus`、`AgentActionStatus` | **能力位可由状态机纯函数推导**，无需前端复制规则 |
| 9 | 本机 node v22.19.0 / npm 10.9.3 可用 | `Get-Command node` | 前端构建可本地 + CI |

> **纠正（批次29 前的重要修正）**：上一轮我判断"工单/结算/对账/调拨/告警列表没挂 `@DataFilter` = 越权面"，**结论过头**。这些表多数**没有站点归属列**，属"全局口径资源"（结算=代理商维度、渠道对账=渠道维度、调拨=跨站任务），受限身份能看列表不等于越权。
> 正确处置：**逐域判定归属**——只有 `work_order`（可经 `device_no`→柜→站解析）**确有归属却未过滤**，这是真实缺口，批次29 处理；无归属资源在文档声明为"全局口径"，并给详情类补 `requireStationAccess`。

## 3. "一个业务要连调多个接口，要不要做编排？"——定论

**不引入编排引擎/工作流引擎**（Flowable/Camunda/自研 DSL 一律不做）。按"断链后业务算失败还是算进行到一半"判：

| 形态 | 例子 | 处置 |
|---|---|---|
| **系统内一次动作** | `/user/order`（内部串 分配→开仓→状态推进） | 保持**后端单入口**（现状正确） |
| **跨角色跨时刻的流程** | 工单 `triage→assign→start→verify→close`；结算 `generate→confirm→paid` | **必须保持多接口**（各自鉴权 + 各自审计），前端只按能力位显示"当前能点哪一步" |
| **现场人驱动的逐台操作** | 调拨 `items/{batteryNo}/in\|out` | 保持逐个（幂等已具备），前端显示进度 `3/20` |
| **读侧要拼多接口** | 柜详情页需 柜+实况+仓+电池健康+告警+未完单 | **这才是真缺口** → 后端补 BFF 聚合视图 |

## 4. 设计决策（8 条）

### 4.1 分层：在 swap-server 内新增 `web/view` 包（BFF 视图层）
- **企业标准形态**：独立 BFF 服务（Node/独立 Java 进程），与业务服务分离部署，多端各自 BFF。
- **本项目**：单进程同模块 `com.swapops.server.web.view`，路由前缀 `/admin/view/**`，只做"取数+拼装+能力位计算"，**不写业务规则**（业务规则仍在各域 service）。
- **代价（如实）**：将来若出现"App 端 + 小程序端 + 后台"三端差异化聚合，`view` 包会变成共享耦合点，届时按端拆 `view/app`、`view/web` 或独立进程——**当前一个消费方，拆分收益不兑现**。→ 已按纪律申报，用户授权后定案。

### 4.2 契约：`view` 层一律 VO，禁止 Entity/`Map` 直出
- 新增 `*ViewVO` / `*VO`（`record` 或 Lombok `@Data`，与项目风格一致用 `@Data`），带 springdoc `@Schema`；
- **存量列表接口本轮不做大爆炸式重构**（76 个接口全改 VO 的回归风险 > 收益）；只重构"前端首批页面会用到的接口"，其余按页面推进逐步替换 —— **范围切分，非简化**（见 §7 边界）。

### 4.3 权限：前端静态路由表 + 后端下发权限码（业界方案②）
- 后端新增 `GET /admin/auth/me`：`{username, role, dataScope, scopeStationNos[], bootstrap, codes[]}`；
- 前端路由 `meta.codes: ['admin:asset:read']`，登录后由全局守卫校验（**实现备注**：未用 `addRoute` 动态注入——路由表本就静态，`addRoute` 只增加首跳时序复杂度而不增加安全性，已在批次30 记录 §四.1 申报）；按钮级 `v-access="'admin:suggestion:manage'"`；菜单由路由表派生（`menuEntries()`）；
- **不做 sys_menu 动态菜单表**（与 `AdminRole` 既有决策一致：菜单结构随代码演进，37 码静态可枚举）。

### 4.4 能力位：`ActionsSupport` 集中计算，后端返回 `allowedActions`
- `com.swapops.server.common.action.ActionsSupport`：`workOrder(Integer status)` / `agentAction(...)` / `transfer(...)` / `settlement(...)` / `alarm(handled)` → `List<String>`（动作码与后端端点名一致，前端直接当按钮标识）；
- **覆盖性单测**：遍历每个枚举的全部 code（含 null / 未知值 → 返回空、不抛），杜绝"新加状态忘了配动作"；
- 列表与详情视图都带 `allowedActions`，**前端零状态判断**。

### 4.5 权限码一致性门禁（防前后端漂移）
- 后端新增 `PermissionCodeContractTest`：读 `../swap-web/src/api/permissions.ts` 中的 `CODES` 值集合，断言 `⊆ AdminRole.ALL_CODES`；**文件不存在则跳过**（保证 swap-web 未落地前 CI 不红）；
- 复用项目已有的"反射覆盖性测试"思路（权限码用码合法性已有测试，本条补前端侧）。

### 4.6 前端契约自动化
- `swap-web` 提供 `npm run gen:api`：用 `openapi-typescript` 从 `document/api/openapi.json` 生成 `src/api/schema.d.ts`，**接口类型来自后端契约而非手写**；
- 提供 `npm run type-check`（`vue-tsc --noEmit`）+ `npm run build` 作为门禁。

### 4.7 鉴权与网络
- token：`X-Admin-Token` 头（与现有 `AdminAuthFilter` 一致），前端存 `localStorage`，Axios 拦截器注入；401 → 清态跳登录；403 → 403 页；429 → 读 `Retry-After` 提示；
- **不引入 Cookie/Session、不做 CSRF 面**（与现有 bearer-token 模型一致）；
- 开发期 **Vite proxy** `/api → http://127.0.0.1:8400`；生产 **nginx** 托管 `dist` + 反代 `/api` → **同源，后端不开 CORS**（现有 `WebConfig` 无 CORS 配置，保持不加）。

### 4.8 数据权限（批次29 唯一的"修 bug"项）
- 工单列表/详情：按 `device_no` 解析柜→站点集合过滤（受限身份），复用 `DataScopeSupport` 语义；实现方式＝受限身份时先解可见柜号前缀集再 `in`，**解析失败 fail-closed 空集**；挂 `@DataFilter("work-order-list")` 保持自检纪律；
- 无站点归属资源（结算/渠道对账/调拨）：**显式声明为全局口径**，并在文档与代码注释写明（不做假动作过滤）；
- `view` 聚合接口内部每个子查询各自走范围工具（防"聚合成为越权旁路"）。

## 5. 首批页面 × 数据依赖 × 接口缺口

| 页面 | 主要数据 | 现状接口够不够 | 需新增（批次29） |
|---|---|---|---|
| 登录 | 账密 | ✅ `/admin/auth/login` | — |
| 框架/菜单 | 身份+权限码 | ❌ 完全没有 | `GET /admin/auth/me` |
| 运营看板 | 满电率/站点可用率/周转 | ✅ `/admin/dashboard/overview`（Map 弱类型） | `GET /admin/view/dashboard`（VO 化，含口径说明字段） |
| 告警中心 | 告警列表 + 是否有工单 + 可执行动作 | ⚠️ 只有裸列表，前端要串 3 个接口 | `GET /admin/view/alarm` |
| 建议单（Agent 闭环） | 列表 + confirm/reject 可用性 | ⚠️ 需自己判状态 | `GET /admin/view/agent-action`（带 `allowedActions`） |
| 工单列表 | 分页 + 动作可用性 | ⚠️ 无范围过滤 + 状态判断在前端 | `GET /admin/view/work-order` |
| 工单详情 | 工单 + 流转日志 + 告警 + 设备 | ❌ 要串 4 个 | `GET /admin/view/work-order/{id}` |
| 柜详情 | 柜 + 实况 + 仓 + 电池 + 未完单 + 最近指令 | ❌ 要串 5~6 个 | `GET /admin/view/cabinet/{cabinetNo}` |
| 订单查询/详情 | 列表 + 费用/支付/指令明细 | ⚠️ 详情要串 | `GET /admin/view/order/{orderNo}` |
| 结算与对账 | 结算单 + 分账流水；差异 + 处置 | ⚠️ 够但需能力位 | `GET /admin/view/settlement/{id}` |

> 矩阵原则：**页面先定，接口倒推**——不做"为聚合而聚合"。上表已按首批 10 页收口，后续页面沿用同规则增补。

## 6. 批次里程碑与验收

| 批次 | 内容 | 验收 |
|---|---|---|
| **29 后端地基** ✅ | `/admin/auth/me`；`ActionsSupport` + 覆盖性单测；工单数据范围修复（db/17 `station_id` + `DeviceOwnershipService` 解析 + `require()` 越域 403）；**7 个** `/admin/view/**`（VO + `@Operation`）；`PermissionCodeContractTest`；剧本 `_c32` | **已达成**：单测 473/473（+37）、`_c32` **54 PASS / 0 FAIL / 1 SKIP**（SKIP=外域工单详情，由 `WorkOrderScopeTest` 覆盖）、`clean verify` 绿（server 行覆盖 70.0%→**71.5%**）、OpenAPI **101→109 paths** |
| **30 前端骨架** ✅ | `swap-web`（Vue 3 + Vite + TS + Element Plus + Pinia + Router + Axios）；Axios 拦截器/token/401/403/429；路由 `meta.codes` 校验 + `v-access`；布局；页面：登录、看板、告警中心（含建议单确认闭环）；`gen:api` + `type-check` + `build`；CI 新增 frontend job；**+ 分页存量缺陷修复（计划外）** | **已达成**：`type-check` 0 error、`build` EXIT=0（主入口 1117→**64 kB**）、`gen:api` 5884 行；后端 **480/480（Skipped 1→0**，`PermissionCodeContractTest` 转实跑）、server 行覆盖 **71.5% (5408/7560)**、`clean verify` BUILD SUCCESS；`_c33` **81 PASS / 0 FAIL / 1 SKIP**；浏览器实机（1440×900）登录→看板→告警翻页/改页长→建议单驳回→404→登出→守卫拦截，**控制台 error 0 条**。证据：`document/block-records/批次30-S8前端骨架与分页缺陷修复.md` |
| **31 业务页与部署** ✅ | 工单列表+详情（五步动作链）、柜列表+详情、订单列表+详情、结算列表+详情；nginx 部署（dist 静态 + `/api` 反代）+ `deploy-web.ps1`/`rollout-web.sh`；`_c34` 页面级契约 + `_c35` 真实浏览器验收 | **已达成**：`_c34` **59/59 PASS**、`_c35` **31/31 PASS**（控制台 error 0 / HTTP≥400 0）、单测 480→**494**、视图端点 7→**11**；范围补齐申报见批次31 记录 §一（柜/结算补列表，订单列表改 VO）；云上执行见 §六 |

## 7. 边界与不做（主动声明）

- **不做**：动态菜单表（sys_menu）、i18n、主题/暗色定制、自研组件库、前端单测重投入（只做 `type-check` + `build` 门禁）、微前端、SSR、图表大屏（看板仅数字 + 必要的简图）；
- **不做**：把 76 个存量管理端接口一次性 VO 化（按页面推进，§4.2）；
- **不做**：BFF 独立进程（理由 §4.1，已申报为有意取舍）；
- **不做**：后端开启 CORS（生产同源反代替代）；
- 前端角色口径：以 SUPER 演示全流程；OPS/FINANCE/SUPPORT 的可见性差异用 `_c32`/`_c33` 断言验证（**不靠手工点页面**验证）。
