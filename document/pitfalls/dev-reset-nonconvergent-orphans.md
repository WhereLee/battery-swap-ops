# 坑位：dev reset 单遍处理不收敛 + 孤儿柜种子缺失 → "台账孤占"残留（已修复·已归零）

> 状态：**已修复 · 已归零**（批次18，2026-09-16）：reset 两阶段重构 + 孤儿柜退役 + 防回归剧本落地。
> 发现于 2026-09-16 P0-3d 写路径压测收尾复核（交接文档 §6.2 follow-up 的推进）。

**现象（修复前）**：
- 写路径压测后对账偶发非资金类违规：`cell-battery-consistency`（cell 引用电池但电池 `cellId=null`，
  或双向指针错位）与 `transfer-ledger`（调拨台账期望仓 vs 电池实际仓不符）。
- **残留集合随每次 reset/压测漂移**：交接文档观察 2 处；本次收尾复核实测 16+2；压测脚本内两时点
  baseline=8 → after=7。数目不同不是"时好时坏"，而是不同时点的快照（明细均未留档）。

**实测证据（修复前，2026-09-16 收尾期 reset 判定实验）**：手动 reset → 对账 **18→8**——
- 修复 13 项（含"孤儿引用"型：cell 引用电池但电池 cellId=null）；
- **新出现 3 项**（cells 1/4/5：前序步骤刚建立的正确绑定被后续步骤 detach 打掉）；
- 始终未修复 3 项（SWAP-T2-224621 的 cells 137/138/139）+ transfer 台账 2 项（09-13 遗留调拨任务）。

**根因（两个独立机制）**：

1. **A. 孤儿柜种子缺失 → 静默跳过**：`DevResetService` 种子编号按"柜排序下标 × 每柜仓数"计算
   （`BAT-{i*cellsPerCabinet+j}`）。SWAP-T2-224621（09-13 历史测试柜，柜号排序在 C 系列之后）对应的
   种子电池（BAT-0121+）**不存在** → 满电分支 `if (battery == null) continue` **静默跳过该仓**——
   其 cell 引用永远停留在旧编号，reset 永不修复。
2. **B. 单遍处理不收敛**：reset 逐仓"边扫边 detach 旧引用 + 绑新种子"，在**交错引用图**上无法一遍收敛：
   后序仓的 detach 会打掉前序仓刚建立的一致绑定（实链：cell5 旧引用=b1，处理 cell5 时 detach(b1)，
   而 b1 刚在 j=1 被绑到 cell1 → cell1→b1 悬挂）。压测并发写（TAKE/RETURN 波次）产生的交错引用图
   为该机制提供输入——**每次 reset 的输出依赖输入状态，不保证幂等收敛到种子基线**。

**影响边界**：
- **不动钱、不动库存计数**：资金类检查（completed-has-payment / settlement-* / statement-* / arrears-*）
  与核心断言（零超卖 / 计费恰一次）不受影响；对账门禁已按"资金类硬门禁 + 非资金类 WARN"分级。
- 实际影响 = 对账噪音与审计解释成本（演示/复盘时需能说清"为什么有残留"——本文档即口径）。

**修复（批次18）**：

1. **两阶段重构**（`DevResetService.reset()`）：阶段一"清场"——全量电池脱仓清引用
   （cell_id/holder 置空、回充电态）+ 逐仓清引用（battery_id/lock 置空、删 Redis 仓锁）；
   阶段二"绑定"——按种子定义逐仓重绑种子电池。输出只依赖种子定义、与输入状态无关 → 任意交错引用图单遍收敛。
2. **种子缺失不再静默**：缺失仓保持空仓并记入返回报告 `seedMissing` + WARN 日志（替代原 `continue`
   静默跳过——孤儿引用永不修复的直接根因）；报告字段 `extrasParked` 改 `batteriesDetached`（清场语义）。
3. **孤儿柜退役**：SWAP-T2-224621 全量盘点（订单/指令/工单 0 引用）→ 备份
   （`diag-archive/t2-retire-20260916/`，不入 git）→ 清理 6 项（alarm 127 + outbox 127 +
   transfer_task 1 + transfer_task_item 2 + cell 12 + cabinet 1）+ Redis 残留键 2 个 → 对账归零。
4. **防回归剧本** `scripts/verify/batch18/_b18_reset_convergence.ps1`：构造"一致但错位"的 4-环交错
   引用图（1↔6↔4↔5↔1）→ reset → 断言 C-001 逐仓精确归位（cell→battery_no 与 battery→cell 双向核验）
   + 对账 0 + 幂等。**该图在旧实现下会残留 2 处悬挂**（cell1→b1、b1=null；cell4→b4、b4=null），
   新实现 PASS——剧本即回归防线。

**账（修复前后）**：

| 时点 | 对账 total | 构成 |
|---|---|---|
| 修复前（压测收尾） | 18 | cell-battery 16 + transfer 2 |
| 批次18 reset 修复后（T2 未退役） | 2 | transfer 2（T2 的 cell 悬挂已被"清场"清掉、种子缺失入报告） |
| T2 退役后 | **0** | 归零，并保持（reset/剧本/服务重启复验均 0） |

**防回归资产**：
- 单测：`DevResetServiceTest` 4 例（主流程 / 清场先于绑定顺序 / 种子缺失入报告 / 幂等）；
- 剧本：`batch18`（交错引用图，见上）+ `_c0`（常规幂等 + E2E，Log 字段同步更新）。

**记录**：批次17 §4 根因定位 → 批次18 修复与归零；此前 `dev-reset-two-bugs.md`（S4-pre 两 bug）
为"单次执行即错"型，本坑位是"执行结果依赖初始状态"型，性质不同、独立存在；本坑位已闭合。
