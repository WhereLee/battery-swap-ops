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
| batch17 | `_p03_write_capacity.ps1` | 写路径容量（TAKE+RETURN 循环：零超卖/计费恰一次/0 错，P0-3） | PASS |
| batch17 | `_p03e_jvm_story.ps1` | JVM 排障对比驱动（96m vs 512m，GC 暂停/吞吐，P0-3） | 手工执行 |
| batch18 | `_b18_reset_convergence.ps1` | dev reset 收敛防回归（交错引用图 → 零残留：逐仓双向核验 + 对账 0 + 幂等） | PASS |
| batch19 | `_c21_chaos_redis.ps1` | 混沌-1 Redis 停机：快速失败（503/2s 500）+ DB 路径存活 + 恢复 rebuild 后对账=基线（P1-10） | PASS 11/11 |
| batch19 | `_c22_chaos_mysql.ps1` | 混沌-2 MySQL 连接抖动：5 轮 KILL 25 连接零挂死 + 幂等键仅 1 单 + 对账 0（P1-10） | PASS 6/6 |
| batch19 | `_c23_chaos_broker.ps1` | 混沌-3 broker 停机：用户路径零影响 + 告警 outbox 不丢 + broker-only 重启后投递自愈（P1-10） | PASS 9/9 |
| batch20 | `_c24_metrics.ps1` | 可观测性：prometheus 端点（无 token 401/带 token 200）+ 6 业务指标 + traceId 落日志（P1-6） | PASS 9/9 |
| batch21 | `_c25_validation.ps1` | 参数校验：7 类非法输入→统一 400 中文消息 + 坏 JSON + 合法路径放行（P1-7） | PASS 13/13 |
| batch21 | `_c26_webhook.ps1` | 告警出站 webhook：RAISED/RECOVERED HMAC 验签 + “接收端停机无影响”隔离轮（P1-11） | PASS 16/16 |
| batch22 | `_c27_data_scope.ps1` | 数据权限（DataFilter）：站点范围账号仅见本域（列表/看板）+ 越域详情/写入 403 + 拒绝无副作用（P1-8） | PASS 24/24 |
| batch23 | （CI 门禁，非本地剧本）IT×3 | 集成测试进 CI：Testcontainers（MySQL8/Redis7）+ failsafe 跑 *IT——换电主链路/对账检出/并发零超卖；另含"IT 失败可红 CI"探针实证（P0-1） | CI 3/3 绿（run 35113915369）+ 探针红（35114318706）→ 回滚绿（35114891453） |
| batch24 | `_c28_shard_ordering.ps1` | 分片保序：同柜乱序/跨代重放注入 + 线程级并行证据 + 台账复核（P0-2；spike 证据 `_spike_fifo_out.txt`） | PASS 15/15 |
| batch24 | `_c29_dual_instance.ps1` | 双实例 30 分钟演练：任务零重复执行/零锁雪崩/终局对账 0（P0-2；流量 `_c29_traffic.ps1`） | PASS 18/18 |
| batch26 | `_c30_hetero_device.ps1` | 异构设备端（Python 标准库）全链换电：下行验签/幂等/TAKE/RETURN/押金退还/台账归属（P2-13） | PASS 25/25 |
| batch27 | `_c31_agent_loop.ps1` | 运维 Agent 闭环：告警→建议单幂等（agent-<id>-<type>）→人工确认→工单+审计 + 反向断言（杀 Agent：平台健康/心跳/告警记录三路无恙；S6/P2-11） | PASS 31/31 |

合计 35 个剧本（`_g1`-`_g8` + `_c0`-`_c28` + `_c30` + `_c31`；`_c29` 为双实例演练分析脚本）+ 容量工程脚本（batch16 读路径复测 / batch17 写路径 + JVM 对比驱动）+ 1 个第三方契约验证脚本 + batch18 防回归脚本（dev reset 收敛）+ batch19 混沌三剧本（Redis/MySQL/broker 停机）+ batch20 指标监控剧本（P1-6）+ batch21 校验/webhook 剧本（P1-7/P1-11）+ batch22 数据权限剧本（P1-8）+ batch23 集成测试门禁证据（P0-1，CI 内跑，非本地剧本）+ batch24 分片保序/双实例剧本（P0-2）+ batch26 异构设备端剧本（P2-13）+ batch27 运维 Agent 剧本（S6/P2-11）；batch9（S5 运维收口）与 batch14（S7 收口）为文档/运维层面，无独立剧本。
P0-4 演示入口：`scripts/demo/_p0_demo.ps1`（不在本索引的剧本口径内，证据 `_p0_demo_out.txt`）。

证据文件：`batch7/_cov_out.txt`（覆盖率门槛）、`batch7/_load_out.txt`（压测+GC 统计）、`batch27/_eval_out.txt`（Agent 评测集 20/20）。

## 通用前置（所有剧本）

1. 中间件就绪：MySQL 3306 / Redis 6379 / RocketMQ 9876+10911+8081（见 `document/knowledge/runbook.md` §1）
2. 快节奏平台：`.local/run-server-fast.bat`（TTL 压缩，参数矩阵见 runbook §6）
3. 模拟器：`.local/run-sim.bat`（http）或 `run-sim-dual.bat`（MQ 剧本）
4. 数据底：必要时先跑 `_c0_dev_reset.ps1`

## 命名约定

- `_gN_*`：S1-S3 主线关卡（gate）；`_cN_*`：S3.8+ 组件/能力验证（capability）。
- 统计口径：脚本只输出可判定 PASS/FAIL 的数字；`_out.txt` 与脚本同批入库（证据链）。
