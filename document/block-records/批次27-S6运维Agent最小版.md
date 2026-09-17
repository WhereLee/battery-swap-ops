# 批次27：S6 运维 Agent 最小版（P2-11）

> 日期：2026-09-17 ｜ 状态：已完成 ｜ 证据：`scripts/verify/batch27/_c31_out.txt`（31/31 PASS）、`_eval_out.txt`（评测 20/20）
> 上游：S4.6 接缝（批次6 `_c15`）+ 观测口（`swap-alarm` topic / `GET /admin/alarm`）。

## 一、范围与拍板记录

- 用户授权：形态 / 执行白名单 / 评测集三项由 AI 全权选择（2026-09-17）；"完成剩余所有内容"。
- 落地三项决策：
  1. **形态**：独立 Maven 模块 `swap-agent`（Spring Boot :8700）——运行时组件走 Java，不用 Python；**零编译依赖**（不依赖 swap-server/swap-contract，纯 HTTP JSON 消费者）→"Agent 挂掉平台无恙"由依赖方向从架构上保证。
  2. **白名单**：**沿用平台既有四类**（建工单/派单/对账/充电策略），不新增"重发开仓"（设备风险高，克制）。
  3. **评测集**：20 题由 AI 起草（suggest 17 + diagnose 2 + summary 1），回归基线锁定全绿。
- 不做：支付沙箱（需真实商户沙箱凭据等用户侧外部资源，非可单方面完成项）。
- **平台侧零改动**：S4.6 接缝（`AgentActionService` propose/confirm/reject + CAS 抢执行权 + `@AdminLog` 审计）原样承压——接缝设计正确性的实证。

## 二、交付物

1. `swap-agent` 模块：
   - `RuleEngine`：14 种告警类型 → 动作/严重度显式映射表；**克制原则**——资金类（欠费）、系统类（任务停摆/死信）、单柜离线 **明确不建议**；未知类型 fail-safe 不动手；同设备同类型聚合去重（源=最早，幂等键 `agent-{alarmId}-{type}`）。
   - `IncidentSummarizer`：窗口内按设备聚合 → 人话摘要（计数降序 + 相对时间）。
   - `DiagnosisService`：设备问答（现状/诊断/建议三段，与建议单同源，不搞两套逻辑）。
   - `PlatformClient`：JDK HttpClient 显式 HTTP/1.1 + 定长 body（沿用 P2-13 协议教训）+ 信封解析 + 幂等键头。
   - `AgentLoopService`：定时轮询（默认关，显式启用）+ 手动 scan；单条提交失败不中断；计数暴露 `/agent/status`。
   - `AgentController`：status / scan / diagnose / summary。
2. 评测集 20 题（`src/test/resources/eval/cases.json`）+ runner（`AgentEvalTest`，stdout + `target/eval-result.txt` 双落 "EVAL RESULT: n/20"）。
3. 剧本 `_c31_agent_loop.ps1`（batch27）：告警→建议单幂等→人工确认→执行→审计 + 反向断言。
4. 单测 29（规则矩阵 8 / 摘要 4 / 问答 4 / 客户端 6 / 主循环 6 / 评测 1）。

## 三、关键设计（可解释性）

- 引擎接口 `SuggestionEngine` 隔离：最小版=确定性规则（可解释/可评测/零幻觉）；将来挂 LLM 只换实现，骨架（扫描/幂等/审计链路）不动。
- Agent 建议标识：idemKey `agent-<alarmId>-<type>` + reason 前缀 `[agent-auto]`；proposer 记录为 break-glass（bootstrap）——生产应给 Agent 独立凭证（`agent-seam.md` §4 边界声明保留）。
- `enabled` 默认 false：独立进程显式启用，避免意外进程骚扰平台；token 经 `SWAP_AGENT_ADMIN_TOKEN` env 注入（零明文），enabled=true 且缺 token 时启动即失败。

## 四、验证证据

| 验证 | 结果 |
|---|---|
| `swap-agent` 单测 | **29/29** |
| 评测集 | **20/20**（`_eval_out.txt`） |
| `_c31` 剧本 | **31/31 PASS**（`_c31_out.txt`） |
| 全量 `mvn clean verify -DskipITs` | **BUILD SUCCESS**——436/436（contract 4 + server 372 + sim 31 + agent 29）；SpotBugs 各模块 BugInstance=0；jacoco 门槛含 agent 65%（实测 **86.2%**，268/311 行） |

`_c31` 关键实测值：
- scan1: `alarms=166 suggestions=16 submitted=16 errors=0`；
- 幂等：A/B 幂等键各 1 行；重扫行数不变；C（ORDER_ARREARS）**0 建议**（资金红线）；
- 确认执行：`status=2 confirmer=bootstrap result={"workOrderId":15,"woNo":"WO93920459044814848","status":1}`；重复确认被 CAS 拒；`admin_op_log` 落 `SUGGESTION_CONFIRM`；
- **反向断言三连**（杀 Agent 后）：平台 health UP、HMAC 心跳照常接受、新告警照常记录 → "平台侧无 Agent 依赖"实证。

## 五、过程中发现的问题（均已修）

1. **AlarmView 未知字段**：平台 view 含 handler/handledTime 而 record 未声明 → Jackson 严格模式抛错（假平台照抄真实契约抓出）→ `@JsonIgnoreProperties(ignoreUnknown=true)`（外部契约演进不得破坏消费者）。
2. **心跳 status 误用**：协议 v1 为数字状态码（1=在线），初版剧本用 "ONLINE" → 400；修正后签名（canonical `cabinetNo|status`）通过。
3. **探测数据组污染**：首版剧本用 SWAP-C-005 造 CABINET_FAULT，与 `_c15` 历史未处理告警同组被聚合（源=更早 id）→ 断言失配；改用无历史柜号 SWAP-C-031（聚合去重行为本身正确，属剧本数据设计问题）。

## 六、边界与未做（如实声明）

- 无自动执行（建议→人工 confirm 是唯一通道）；单级审批；失败不重放（需重新建议）。
- 轮询（默认 30s）非推送（观测口已有 MQ topic，推送形态可作后续演进）；MCP server 未部署（工具目录见 `agent-seam.md` §2）。
- 无 LLM（确定性规则为基座，接口已预留）；Agent 凭证为 break-glass（生产应独立凭证 + 更细白名单）。
- 支付沙箱未做（外部资源依赖）。
