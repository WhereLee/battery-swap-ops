# scripts/verify —— 实机剧本与证据索引

> 剧本 = 全栈实机验证（SQL/Redis/MQ 语义 + 装配问题的唯一防线，单测覆盖不到）。
> 每个 `*.ps1` 同目录有 `*_out.txt` 统计证据（判定 PASS 的落档）；大体积原件（jtl 等）归档 `diag-archive/`（不入 git）。

## 总览

| 批次 | 剧本 | 主题 | 状态 |
|---|---|---|---|
| batch1 | `_g1_open_loop.ps1` | 开仓指令-事件闭环（S1） | PASS |
| batch2 | `_g2_swap_e2e.ps1` | 换电全链路（下单→分配→取还→计费，S2） | PASS |
| batch2 | `_g3_concurrency.ps1` | 并发分配原子占仓（S2） | PASS |
| batch3 | `_g4_replay.ps1` | 幂等重放（bootId,eventSeq 序守卫，S3） | PASS |
| batch3 | `_g5_outage.ps1` | 断链重连事件补送（S3） | PASS |
| batch3 | `_g6_mq.ps1` | MQ 双通道去重（S3，历史部分通过，见批次3记录） | 记录 |
| batch3 | `_g7_reconcile.ps1` | 定时对账销账/重试（S3） | PASS |
| batch4 | `_g8_graceful.ps1` | 优雅停机（S3.8） | PASS |
| batch4 | `_c0_dev_reset.ps1` | 联调数据重置 | PASS |
| batch4 | `_c1_cache.ps1` | 两级缓存一致性（S3.8） | PASS |
| batch4 | `_c2_ratelimit.ps1` | 令牌桶限流（S3.8） | PASS |
| batch4 | `_c3_breaker.ps1` | 熔断舱壁（S3.8） | PASS |
| batch4 | `_c4_outbox.ps1` | outbox 中继不丢事（S3.8） | PASS |
| batch4 | `_c5_deadlock.ps1` | 死锁重试自愈（S3.8） | PASS |
| batch4 | `_c6_redis_ops.ps1` | Redis 恢复基线（S3.8） | PASS |
| batch5 | `_c7_workorder.ps1` | 工单 SLA 闭环（S4.4） | PASS |
| batch5 | `_c8_dashboard.ps1` | 看板聚合指标（S4.5） | PASS |
| batch5 | `_c9_plan_crud.ps1` | 套餐 CRUD + 校验（S4.5） | PASS |
| batch5 | `_c10_asset_crud.ps1` | 资产 CRUD（S4.5） | PASS |
| batch5 | `_c11_user_admin.ps1` | 用户管理 + 权限边界（S4.5） | PASS |
| batch6 | `_c12_battery_health.ps1` | 电池健康循环计数（S4.1） | PASS |
| batch6 | `_c13_transfer.ps1` | 调拨调度-理仓（S4.2） | PASS |
| batch6 | `_c14_charge_policy.ps1` | 充电策略下发-回滚（S4.3） | PASS |
| batch6 | `_c15_agent_seam.ps1` | Agent 建议单接缝（S4.6） | PASS |
| batch7 | `jmeter/swap-ops-load.jmx` | 容量压测（读路径 4 接口，S5.2） | PASS |
| batch8 | `_c16_overdue_paths.ps1` | OVERDUE 出口双路径（超期归还完成 / 超长转人工+告警，S5 审查回归） | PASS |
| batch10 | `_c17_rbac.ps1` | 管理端 RBAC/审计/break-glass（S7 WP-A） | PASS |
| batch11 | `_c19_channel_recon.ps1` | 渠道对账 T+1（账单导入/四类差异/处置，S7 WP-C） | PASS |
| batch12 | `_c20_user_service.ps1` | 报障→工单 / 欠费闭环 / 优惠券 / 站内信（S7 WP-D） | PASS |
| batch13 | `_c18_settlement.ps1` | 代理分润结算（分账/冲正/补行/结算单，S7 WP-B） | PASS |
| batch15 | `_py_contract_client.py` | 第三方（Python 标准库）契约客户端：心跳/事件验签正+负例（P1-12） | PASS |
| batch16 | `_p03_read_capacity.ps1` | 读路径容量方法论复测（预热+稳态 20/50/100，P0-3） | PASS |

合计 29 个剧本（`_g1`-`_g8` + `_c0`-`_c20`）+ 1 个容量压测脚本 + 1 个第三方契约验证脚本；batch9（S5 运维收口）与 batch14（S7 收口）为文档/运维层面，无独立剧本。
P0-4 演示入口：`scripts/demo/_p0_demo.ps1`（不在本索引的剧本口径内，证据 `_p0_demo_out.txt`）。

证据文件：`batch7/_cov_out.txt`（覆盖率门槛）、`batch7/_load_out.txt`（压测+GC 统计）。

## 通用前置（所有剧本）

1. 中间件就绪：MySQL 3306 / Redis 6379 / RocketMQ 9876+10911+8081（见 `document/knowledge/runbook.md` §1）
2. 快节奏平台：`.local/run-server-fast.bat`（TTL 压缩，参数矩阵见 runbook §6）
3. 模拟器：`.local/run-sim.bat`（http）或 `run-sim-dual.bat`（MQ 剧本）
4. 数据底：必要时先跑 `_c0_dev_reset.ps1`

## 命名约定

- `_gN_*`：S1-S3 主线关卡（gate）；`_cN_*`：S3.8+ 组件/能力验证（capability）。
- 统计口径：脚本只输出可判定 PASS/FAIL 的数字；`_out.txt` 与脚本同批入库（证据链）。
