# 知识：Agent 接缝（S4.6）——两段式动作 + MCP 工具清单

> 代码：`AgentActionService` / `AdminAgentActionController`；表 `agent_action`（db/09）。
> 目标：项目二运维 Agent（独立仓库）**只消费**平台能力——平台不依赖 Agent，Agent 缺席/出错不影响主流程。

## 1. 设计原则

1. **观测口（接缝①，已实现）**：告警事件上行 `swap-alarm`（outbox 驱动、断网不丢）；信封
   `{alarmId, alarmType, deviceType, deviceNo, content, handled, createTime, traceId}`。
2. **动作口（接缝②，本次）**：**两段式**——建议（无副作用）→ 人工确认（才执行）；幂等键 + 全量审计。
3. **白名单**：只开放运维类动作；**资金类（退款/扣费）不开放**——Agent 可以做分析，不能动钱。

## 2. 动作目录（MCP 工具清单）

| MCP 工具名 | 用途 | HTTP 端点 | 入参 | 风险 | 人工确认 |
|---|---|---|---|---|---|
| `list_alarms` | 查未处理告警 | `GET /admin/alarm?handled=0` | limit | 只读 | 否 |
| `get_dashboard` | 运营总览 | `GET /admin/dashboard/overview` | — | 只读 | 否 |
| `get_battery_health` | 电池健康档案 | `GET /admin/battery/{no}/health` | batteryNo | 只读 | 否 |
| `list_work_orders` | 工单列表 | `GET /admin/work-order` | status/page | 只读 | 否 |
| `propose_create_work_order` | 由告警建议建工单 | `POST /admin/agent-action`（Idempotency-Key） | actionType=CREATE_WORK_ORDER_FROM_ALARM, params.alarmId, params.severity? | 低 | **是（confirm）** |
| `propose_assign_work_order` | 建议派单 | 同上 | actionType=ASSIGN_WORK_ORDER, params.workOrderId/handlerId | 低 | **是** |
| `propose_run_reconcile` | 建议触发对账 | 同上 | actionType=RUN_RECONCILE | 低 | **是** |
| `propose_charge_policy` | 建议下发充电策略 | 同上 | actionType=APPLY_CHARGE_POLICY, params.cabinetNo/priority/windows[] | 中 | **是** |
| `confirm_action` | 人工确认执行 | `POST /admin/agent-action/{id}/confirm` | id | — | 人执行 |
| `reject_action` | 驳回建议 | `POST /admin/agent-action/{id}/reject` | id, remark | — | 人执行 |

- 只读工具可直接被 Agent 调用；**任何写动作只能"建议"**，执行必须人工 `confirm`；
- 建议单列表（`GET /admin/agent-action`）即审计视图：谁提的（proposer）、为什么（reason）、谁批的（confirmer）、结果（result/error）；
- 幂等：`Idempotency-Key` 头 → `agent_action.idem_key` 唯一键；重复提交返回既有建议单；
- 执行幂等：确认时 CAS `PROPOSED→EXECUTING` 抢执行权，并发/重复确认只有一次执行。

## 3. 与 MCP 的关系

MCP（Model Context Protocol）是 LLM Agent 发现/调用工具的通用协议。本阶段交付**工具目录与端点语义**
（上表 + OpenAPI 注释），将来部署 MCP server 只是"把上表包一层"——业务代码零改动。
换言之：**接缝是契约，MCP 是其中一种接线方式**（HTTP/事件同样成立）。

## 4. 边界（如实声明）

- 无自动执行（没有"Agent 直接执行"的通道）；无多级审批（单级 confirm）；
- 动作结果 JSON 落库，但不重放（失败需重新建议）；
- Agent 身份仅以 `proposer` 字符串标识（无 Agent 侧认证体系；管理端点仍由 AdminTokenFilter 保护——**当前建议入口也是管理端令牌**，生产形态应给 Agent 独立凭证与更细白名单，roadmap）。

## 5. S6 最小版落地（批次27，2026-09-17）

- **消费方**：独立模块 `swap-agent`（:8700，零编译依赖——纯 HTTP JSON 客户端；平台侧对 Agent 零依赖，杀进程反向断言三连实证）；
- **引擎**：确定性规则表（14 类告警 → 动作/严重度；资金类/系统类/单柜离线**明确不建议**——克制原则；未知类型 fail-safe）；
  接口 `SuggestionEngine` 隔离，将来挂 LLM 只换实现，骨架（扫描/幂等/审计链路）不动；
- **幂等**：建议键 `agent-<alarmId>-<type>`（平台 `idem_key` 唯一键兜底）；reason 前缀 `[agent-auto]`；
- **评测**：20 题（suggest/diagnose/summary 三类），回归基线 20/20（`scripts/verify/batch27/_eval_out.txt`）；
- **闭环证据**：`_c31` 31/31（扫描→建议单→人工确认→工单+审计；含杀 Agent 反向断言）；
- proposer 记录为 `bootstrap`（break-glass 令牌）——生产独立凭证的边界声明（§4）不变。
