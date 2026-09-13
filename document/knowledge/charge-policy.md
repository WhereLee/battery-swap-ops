# 知识：充电策略与仿真（S4.3）

> 代码：`ChargePolicyService`（平台）/ `AdminChargePolicyController` / `CommandDispatchService.dispatchPolicy`；
> sim：`ChargePolicy`、`CabinetSim.chargeTick`、`ChargingScheduler`；表 `charge_policy`（db/08）。
> 证据：`scripts/verify/batch6/_c14_charge_policy.ps1`（谷时充电/峰时冻结/功率上限/窗口校验/成本对比/版本单调）。

## 1. 分工（平台策略、柜侧执行）

| 层 | 职责 |
|---|---|
| 平台 | 定义时段窗口（功率上限+电价+优先级）、版本管理、下发与审计（command_log）；计成本 |
| 柜侧 | **只执行功率上限**（不感知电价）；按预算给电池分配功率；窗口未覆盖=0W（保守不充） |

- canonical 含版本：`cabinetNo|POLICY|version|commandSeq` —— 防篡改 + 版本单调（新应用/同幂等/旧拒绝）；
- 下发失败落 `FAILED`，可 `reapply`（同版本重投安全：柜侧幂等）。

## 2. 仿真模型（明示假设，不假装真实物理）

- 每 tick：预算 = 当前小时窗口功率；`count=floor(预算/单颗上限)` 颗先充，其余排队；
  每颗功率 = `min(单颗上限, 预算/count)`；SOC 增量 = `P×η×Δt×倍速 / 容量 ×100`；
- 参数：单颗上限 400W、容量 1150Wh（48V24Ah）、η=0.9、`charge-speed-factor` 仅联调放大时间；
- 这是**行为级仿真**（用于验证策略下发与功率约束逻辑），不是电化学模型——文档声明。

## 3. 证据口径

- 谷时（当前小时 2000W/30分）6s：SOC 合计 +144，实测功率 1200W ≤ 2000W；
- 峰时（0W/200分）4s：SOC 冻结、功率 0（排队语义）；
- 窗口必须连续覆盖 0~24（缺口/重叠/越界拒绝——缺省 0W 不符合生产直觉，显式失败）；
- 成本对比（10kWh 全谷 vs 全平 150分）：300 vs 1500 分（策略收益可量化）；
- 版本单调：v→v+1 应用，sim `policyVersion` 与平台记录一致（实机断言）。

## 4. 本轮实机抓到的装配坑（单测测不出）

sim 应用此前**没有 `@EnableScheduling`**（心跳用的是自建 ScheduledExecutorService），
新加的 `@Scheduled ChargingScheduler` 从未触发——剧本表现为"策略已下发但 SOC 不动"。
已补注解；教训：**@Scheduled 新组件必须确认容器启用了调度**（与 Spring 装配坑同一族问题）。

## 5. 边界

- 柜端不感知电价（费用在平台侧计算，避免把计费逻辑烧进设备）；
- 联调为**同步应用**回执；真实柜异步确认需新增 `POLICY_APPLIED` 事件（协议扩展 roadmap）；
- 未做多柜聚合策略/需量控制（总功率不超过站点变压器容量）——站点级策略为后续演进。
