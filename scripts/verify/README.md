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
| batch29 | `_c32_admin_view.ps1` | S8 前端地基：`/admin/auth/me` 身份与 37 权限码 / BFF 视图聚合（看板·告警·建议单·工单·柜·订单）/ 能力位逐态断言（工单五步链）/ 柜详情不泄露 secret / 受限身份本域可读·越域 403·无归属工单 fail-closed / db17 幂等 | PASS 54/54（SKIP 1，由单测覆盖） |
| batch30 | `_c33_paging.ps1` | 分页回归网（S8）：openapi 中全部 **13 个分页端点** × 5 类断言——`limit` 生效 / `total>=rows` / **不出现“有行但 total=0”的静默特征** / 第2页首行≠第1页 / `limit=100000` 封顶 200；另含前端权限码文件存在·数量·无重复 3 项（防止 `PaginationInnerInterceptor` 被误删后静默退化为全表查询） | PASS 81/81（SKIP 1：transfer 表为空） |
| batch31 | `_c34_pages.ps1` | 页面级契约（S8 批次31）：8 个业务页 × 端点形状 + **能力位逐态一致**（工单五步链 / 订单 refund-vs-reversal / 结算 confirm-paid-none）+ **内部列不外泄**（idemKey / 柜 secret / eventKey）+ 结算守恒 + 分页生效 + **前端 types.ts 字段镜像**（8 个 VO）+ 8 个页面已懒加载路由并挂 `meta.codes`。**批次38 起自写证据**（`Emit`+`Save-Evidence`，每个退出路径落盘 `_c34_out.txt`） | PASS 59/59 |
| batch31 | `_c35_console.ps1`（+ `_c35_console.mjs`） | 真实浏览器验收（S8 批次31）：**零依赖 CDP 驱动**（Node 22 内置 WebSocket + 本机 headless Chrome，不引入 puppeteer/playwright）——`vite preview` 托管**构建产物 dist/**（等价 nginx 形态）→ 真实登录表单 → 逐页点击走查（列表→详情靠点链接，走应用内路由）→ 路由守卫与 `?redirect=` 回跳 → 1440px 无横向溢出 → `undefined/NaN` 零命中 → **控制台 error 0 / HTTP≥400 0 / 请求失败 0**。**批次38**：证据由 `.ps1` 自写；截图改为扩视口到不动点后再截（满高外壳内容在 `el-main` 内滚动，原先"整页"实为一屏），并把真实捕获尺寸打进记录 | PASS 31/31（截图 `.local/b31-shots/`，1440×900…2109，不入 git） |
| batch38 | `_c38_ui_probe.ps1`（+ `_c38_ui_probe.mjs`） | **布局/对比度探针**（批次38）：10 条路由（详情页按"点首行链接→读 `location.pathname`"发现，因工单/结算单链接路由参数是 `row.id` 而非可见单号）× 6 条硬门禁——**页面横向溢出 / 非设计性裁切 / 表头与表体错列 / 表头文字截断 / 标签对比度 4.5:1（WCAG 1.4.3，12px 属正常字号）/ 零尺寸标签** + 3 条事实项（内容高与滚动容器 / 整列无可见文字（区分 `innerText` 与 `textContent`，可抓"绑定了值但看不见"）/ 各列空值）。动机：视觉模型复核报出的疑点当时**无法被证伪**，遂逐条落成判据 | PASS 10 页 hardFailures=0（270 标签 6.00–8.57:1 / 89 列零错位；`_c38_report.json`） |
| batch39 | 同 `_c38_ui_probe`（扩面，无新脚本） | **对比度门禁扩到全部文字元素**（批次39）：不再按固定选择器列表测，改为**遍历所有持有自身文本的可见叶子元素**，按 WCAG 1.4.3 分级判定（默认 **4.5:1**，仅大字号 ≥24px 或 ≥18.66px 加粗才允许 3:1；显式豁免 `[disabled]`/`.is-disabled`/`aria-hidden`），按 `(选择器,颜色,阈值)` 去重报告。扩面后确认失败是**令牌级**的（`--el-text-color-secondary` 2.87–3.08:1、`--el-color-primary` **2.78:1 双向失败**、warning 作文字 2.19:1），令牌级修复后升为第 7 条硬门禁 | PASS **1936 个文字元素 0 不达标** + 标签 0 不达标（10 页 hardFailures=0） |
| batch40 | `_c39_index_audit.ps1` | **索引/访问路径审计**（批次40）：①审计——用 MySQL 自己的逐语句统计（`performance_schema.events_statements_summary_by_digest`）列出服务器记录为**未走索引**的语句，按表大小与每次扫描行数分 `REAL-GAP` / `benign` / `unclassified` 三类（10 行小表全扫属正常，5,000 行表每次扫 4,000 行是缺陷；"看不出"与"没问题"分开报）；②计划——`EXPLAIN` 断言 `type≠ALL` 且 `key≠NULL`，外加**索引前导列存在性**的 schema 断言；③**因果证明**——读计数器 → 打真实 HTTP 流量 → 再读，断言"执行数涨、无索引次数不涨、每次扫行数塌到约等于返回行数"；④**无界读守类**——应用 schema 里既无 `WHERE` 又无 `LIMIT` 且每次返回 >500 行的 `SELECT`：最近 30 分钟内出现即 FAIL、更早记 INFO（补记动机：第一条审计发现的未归属语句与其考古，见块记录 §八）。全程只读（不 truncate performance_schema、不改服务器变量） | PASS 15/15（payment_record 8,090→**1 行/次**、alarm "全部"页签 4,032→**20 行/次**；修 `db/19`；0 条 active 无界读 / 2 条 historical） |
| batch41 | 同 `_c38_ui_probe`（扩面，无新脚本） | **界面门禁扩面：弹窗 / 占位符 / 非文本边界**（批次41）：探针改为可变作用域（`probeExpression(scopeExpr)`），新增**弹窗轮**——按按钮文本打开（`allowedActions` 决定是否存在，缺失记 SKIP 带原因）→ 等 `.el-dialog` 可见 → **以弹窗元素为作用域**重跑全套测量（Element Plus 把弹窗 teleport 到 `<body>`，整页测量会把页面数字重复计一遍）→ 结果并入硬门禁；新增**占位符测量**（含 `querySelectorAll` 永远取不到的 `::placeholder` 伪元素，走 `getComputedStyle(el,'::placeholder')`）；新增**非文本边界**（WCAG 1.4.11）测量但**刻意不设门禁**（适用范围是判断题、修它等于重做全站边框，属视觉设计决策）。首跑即抓到真缺陷：占位符 **2.30:1**（批次39 修了 secondary 令牌、漏了 placeholder 令牌，而页面上没有占位符、只有打开表单才出现） | PASS 10 页 + 1 弹窗 hardFailures=0（修 `--el-text-color-placeholder: #6f747c` = 4.70:1；非文本边界 175 个 / 15 个低于 3:1，只报不改） |
| batch43 | `_c40_ui_negative_control.ps1`（+ `.mjs`） | **界面门禁可红性实证**（批次43）：先测干净基线（判据必须为假）→ 注入一类缺陷 → 判据必须为真 → 移除后必须重新测得干净（证明探针"对注入反应"而非"脏了就粘住"）。四类注入：标签对比度（`--el-tag-text-color:#eee`）、单元格裁切（首列限宽 40px）、页面横向溢出（3000px 块）、表头错列（表头右移 30px）。**用门禁自己的判据代码**（`import { probeExpression }`，不复制不重写） | PASS 10/10（70 个标签/最差 1.05:1、20 个裁切、溢出 true、11 列错位；**负控首跑即发现门禁盲区** → 见批记录 §三） |
| batch43 | `_c41_index_negative_control.ps1`（batch40 目录） | **索引审计可红性实证**（批次43）：`DROP INDEX idx_order_id`（复现批次40 缺陷形状）→ `_c39` 必须红 → 重放 `db/19`（幂等，放在 `finally`，中断也不会留下缺失索引）→ 必须绿。断言"红的是同一种病"：FAIL 行须为访问路径断言，且签名与批次40 记录一致 | PASS 9/9（红：`type=ALL key=NULL rows=7875` + 6 条 FAIL；绿：`GATE-INDEX PASS`） |
| batch32 | `_g32_out.txt`（无脚本，门禁探针日志） | 事务自调用守卫**可红性实证**：把 `@Transactional` 加回 `RefundService.applyMoney`（复原 P0 缺陷形状）→ 守卫 `Failures: 1 / BUILD FAILURE`；还原 → `Failures: 0 / BUILD SUCCESS`（批次32） | PASS（探针红→还原绿） |
| batch33 | `_c36_xff.ps1` | 登录限流不可被 header 绕过：10 次**各带不同伪造 X-Forwarded-For** 的爆破 → 前 5 次 400、后 5 次 **429**（同一个桶）；等 6s 窗口过后恢复（是窗口不是封禁） | PASS 5/5 |
| batch35 | `_c37_refund_ledger.ps1` | 资金台账幂等键分型（db/18）：旧 `uk_order_type` 已删 / 生成列 `idem_key` 存在；**同订单第二笔 REFUND 行可插入**（修复前 1062）/ 重复 trade_no 仍拒 / **同订单第二笔 BALANCE_FEE 仍拒**（扣费幂等闸未削弱）/ 无订单归属行不受约束 / 探针清理；实机两笔真实部分退款（ADMIN_MANUAL 100 + ADMIN_REVERSAL 200）→ 订单详情 2 条 REFUND 流水、台账合计=退款单合计 | PASS 13/13 |

合计 **56 个剧本与验证脚本**（`_g` 主线 ×8 + `_c` 能力 ×43 + `_p03` 容量 ×3 + `_b18` 防回归 ×1 + Python 契约客户端 ×1，
`Get-ChildItem -Recurse -Include _g*.ps1,_c*.ps1,_p*.ps1,_b*.ps1,_py_*.py` 实测计数），
另有 4 个辅助文件（`_c26_mock_receiver.py` webhook 接收端、`hetero_device.py` 异构设备端库、
`_cdp.mjs` 共用 CDP 客户端、两个浏览器剧本主体 `_c35_console.mjs` / `_c38_ui_probe.mjs`）；
batch9（S5 运维收口）与 batch14（S7 收口）为文档/运维层面，无独立剧本。
P0-4 演示入口：`scripts/demo/_p0_demo.ps1`（不在本索引的剧本口径内，证据 `_p0_demo_out.txt`）。

> **批次38 起证据自写**：`_c34`/`_c35`/`_c38` 三个剧本由自身命令产 `_*_out.txt`（不再人工 tee）。
> 起因是一次重构让 `_c35` 少跑 6 项检查、又让 9 项检查因 NaN 超时在 0ms 内全红，
> 而文件仍写着 31/31 —— 详见 `document/pitfalls/check-coverage-lost-in-refactor.md`。

证据文件：`batch7/_cov_out.txt`（覆盖率门槛）、`batch7/_load_out.txt`（压测+GC 统计）、`batch27/_eval_out.txt`（Agent 评测集 20/20）。

## 通用前置（所有剧本）

1. 中间件就绪：MySQL 3306 / Redis 6379 / RocketMQ 9876+10911+8081（见 `document/knowledge/runbook.md` §1）
2. 快节奏平台：`.local/run-server-fast.bat`（TTL 压缩，参数矩阵见 runbook §6）
3. 模拟器：`.local/run-sim.bat`（http）或 `run-sim-dual.bat`（MQ 剧本）
4. 数据底：必要时先跑 `_c0_dev_reset.ps1`

## 命名约定

- `_gN_*`：S1-S3 主线关卡（gate）；`_cN_*`：S3.8+ 组件/能力验证（capability）。
- 统计口径：脚本只输出可判定 PASS/FAIL 的数字；`_out.txt` 与脚本同批入库（证据链）。
