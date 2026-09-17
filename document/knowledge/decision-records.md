# 决策记录（ADR 十问 · 可白板复述）

> 日期：2026-09-16（批次25，P1-9）
> 目的：把散落在 60+ 篇文档里的取舍浓缩成 15 分钟能白板讲完的 10 条。
> 每条统一五段：**背景 / 选项 / 决策 / 代价 / 何时该换**。
> 口径：以下均为本项目实际实现（代码位置随文给出），非教科书泛论。

---

## ADR-1 状态与资金：CAS 条件更新 vs 悲观锁

- **背景**：订单状态机、钱包余额、套餐次数、电池持有人都是并发写热点；重复事件/重试/双实例都会造成并发推进。
- **选项**：① `SELECT ... FOR UPDATE` 悲观锁；② 版本号乐观锁（额外 version 列）；③ **条件更新 CAS**（把守卫写进 WHERE，判 rows）。
- **决策**：③。所有关键跃迁都是"一条 UPDATE 同时完成守卫 + 推进"：`status=from` 才迁移、`balance>=amount` 才扣、`holder_user_id IS NULL` 才绑定、`lock_order_id IS NULL` 才落锁。
  - 位置：`OrderEventService.cas`、`WalletService`、`OrderEventService.assignHolder`、`AllocationService.allocate`、`DeviceEventService.handle`（序守卫）。
- **代价**：高冲突下"失败即业务拒绝"需要调用方区分"并发失败"与"业务不满足"；无法表达"多行原子读改写"的复杂逻辑（那种场景要回到事务）。
- **何时该换**：出现真正的多行不变量（跨表账本平衡）或冲突率高到重试成本超过锁等待成本时，改用事务 + 悲观锁；引入 version 列的时机是"同一行需要区分'谁改的'而不是'能不能改'"。

## ADR-2 分配：Redis Lua 原子弹仓 + DB 条件更新兜底（双层）

- **背景**：满电仓分配是最高并发点（多人同时抢同一柜的有限库存），既要快又不能超卖。
- **选项**：① 纯 DB 条件更新（简单但热点行竞争）；② 纯 Redis（快但数据丢失即超卖）；③ **双层**：Redis 承担"并发收敛"，DB 承担"最终判官"。
- **决策**：③。Lua 内 `SPOP` 弹候选 + `SET NX EX` 打仓锁一次完成（同刻并发只有一个拿到同一候选），随后 DB `lock_order_id IS NULL` 条件更新确认；候选内容校验不过 → 回滚锁继续弹（最多 5 次）。
  - 位置：`AllocationService.ALLOC_SCRIPT` / `allocate` / `unlockAndDeleteLock`。
- **代价**：两套状态需要"同步纪律"——集合由事件与管理态变更驱动刷新，启动/数据修复走 `rebuildFromDb`；一致性窗口内会弹到脏候选（用重试消化）。
- **何时该换**：库存量级大到 Redis 集合本身成为热点（单柜数千仓）时，改为分桶集合或 DB 队列化分配；若业务允许"少卖不超卖"，可退化为纯 DB + 限流。

## ADR-3 指令与事件的关联：commandSeq 会话绑定 vs 时间窗匹配

- **背景**：平台下发开仓指令，设备回上报事件（门开/取电/还电），必须把事件精确关联到"哪一笔订单的哪一次下发"。
- **选项**：① 按 (柜号 + 时间窗 + 仓号) 模糊匹配；② **commandSeq 显式回带**（设备把指令序号原样带回）；③ 设备侧维护会话状态机（协议变重）。
- **决策**：②。下发时 `prepareOpen` 生成 seq 落指令台账并写入订单 `open_command_seq`；事件回带 seq → `(cabinetId, openCommandSeq, status)` 精确定位 + CAS 推进；不带 seq 的事件只走设备台账不进订单域。
  - 位置：`CommandDispatchService.prepareOpen`、`SwapOrderService.sendOpenCommand`、`OrderEventService.findBySeq`、`CommandLogService.markArrivedBySeq`。
- **代价**：协议多一个字段且设备必须正确回带（模拟器/异构端都要实现）；指令台账与事件台账需要"未命中不抛"的宽容（事件可能先于台账落库）。
- **何时该换**：设备侧无法持久 seq（掉电即丢）时，改用"柜内单调会话号 + 平台侧会话表"；若一个柜同时存在多个并发开仓（多门同时开），seq 需要扩展为 (seq, cellNo) 复合键。

## ADR-4 跨域事件发布：Outbox 中继 vs 事务内直发 MQ

- **背景**：业务事务里要"发通知/发结算事件"，直接发 MQ 会遇到"事务回滚但消息已发"或"消息发了但事务失败"的双写不一致。
- **选项**：① 事务内直发（不一致）；② 事务后发（丢消息风险）；③ **Outbox 表 + 中继任务**（同事务写表，异步投递）；④ 事务消息（RocketMQ half message，依赖 broker 能力与回查逻辑）。
- **决策**：③。业务事务内写 outbox（NEW），`OutboxRelayTask` 轮询 → 按类型路由发送 → SENT；失败退避重试（base×N），超限 → DEAD + `OUTBOX_DEAD` 告警；多实例由租约锁互斥。
  - 位置：`db/04-outbox.sql`、`OutboxRelayTask.relay`、指标 `outbox 积压/死信`（批次20 Prometheus）。
- **代价**：投递是 at-least-once（下游必须幂等）；引入一张表 + 一个任务的运维面；延迟高于直发（轮询节拍）。
- **何时该换**：需要"事务与消息严格原子"且 broker 支持事务消息时换 ④；投递延迟敏感（<100ms）时改为"事务后直发 + outbox 兜底补偿"的混合形态。

## ADR-5 定时/延迟：自研 Redis ZSET 延迟任务器 vs Quartz

- **背景**：业务需要大量"短周期、一次性、由业务态触发"的超时推进（预占 120s、取电 120s、归还超期 N 小时），同时有少量周期扫描（对账/巡检/离线扫描）。
- **选项**：① Quartz（JDBC JobStore，cron 强、集群行锁重）；② Spring `@Scheduled` + 自己扫表（简单但多实例要自己做互斥、扫表压力）；③ **ZSET 时间轮 + ZREM 原子领取**（延迟任务）+ `@Scheduled` + 租约锁（周期任务）。
- **决策**：③ 组合。延迟任务走 ZSET（score=执行时刻，member=taskId，HASH 存载荷/重试计数），领取=逐个 ZREM 返回 1 才算领到（多实例天然互斥，无需额外锁）；周期任务走 `@Scheduled` + `JobLockService` 租约锁。
  - 位置：`common/delay/DelayQueueService`、`DelayScheduler.poll`、`common/lock/JobLockService`；对照：范例项目 `inteink-faster` 用 Quartz（cron 型任务为主）。
- **代价**：at-least-once（领取后实例崩溃丢本轮，靠业务扫描兜底）；没有 cron 日历、任务历史、重跑治理等调度平台能力；自研代码需要自己测（已有单测 + 双实例演练）。
- **何时该换**：需要 cron 表达式/任务依赖编排/可视化重跑/跨机房调度时换 Quartz 或 xxl-job；延迟任务量级到百万级在途时改为分片时间轮（多 key）或专用延迟队列（RocketMQ 定时消息）。

## ADR-6 事件通道保序：单队列全局序 → FIFO message group 分片序（演进）

- **背景**：设备事件必须按序处理（乱序会把台账写成错的），最初实现是"单 topic 单队列 + 消费并发度=1"（全局串行）——保序最强但吞吐天花板最低。
- **选项**：① 保持全局单队列（吞吐受限）；② **按柜分片**：发送端设 FIFO message group=cabinetNo（同柜同队列），消费端按柜 hash 到固定 worker（同柜串行、跨柜并行）；③ 多 topic 按柜 hash 路由（运维面变大）；④ 消费端并行 + 业务侧排序缓冲（复杂度与内存风险最高）。
- **决策**：②。保序不变量从"全局"降为"**每柜**"——序守卫本就按 `(cabinetNo, bootId)` 判定，跨柜事件之间本来无顺序语义，业务不变。
  - 位置：`MqEventReporter.buildMessage`（`setMessageGroup`，开关 `swap.sim.mq.fifo-group`）、`DeviceEventMqConsumer`（`dispatch`/`shardKey`/`workerIndexFor`，workers=4/batch=16/invisible=30s）。
  - **spike 实测结论（关键）**：服务端只保证"同柜投递序"，**未 ack 的同柜后续仍会投递** → 柜内串行必须由消费端保证（不能依赖 broker hold）；未 ack 消息在 invisible 到期后重投，由业务幂等吸收。证据 `scripts/verify/batch24/_spike_fifo_out.txt`、剧本 `_c28` 15/15。
- **代价**：并行度受柜数与 worker 数限制（worker 内仍是串行）；同柜积压时后续消息可能超 invisible 被重投（靠序守卫拒绝重复）；需要"分片键稳定"（柜号不可变）。
- **何时该换**：单柜事件速率超过单 worker 处理能力时，把分片键细化为 (柜, 事件类型) 或引入柜内序号排序缓冲；柜数远超 worker 数时按负载动态调整 worker 数（配置项已留）。

## ADR-7 缓存一致性：两级缓存 + Pub/Sub 失效 + TTL 兜底 + 延迟双删

- **背景**：套餐目录/站点元数据/看板总览是高频只读、低频变更的展示数据；读压力直接打 DB 会拖垮读路径。
- **选项**：① 只用 Redis（每次读一次网络往返）；② 只用本地缓存（多实例失效难）；③ **L1 Caffeine + L2 Redis + Pub/Sub 广播失效 + TTL 兜底**；④ 订阅 binlog（Canal）做失效（组件面变大）。
- **决策**：③。读 L1→L2→回源并回填；穿透=空值短 TTL（NULL 哨兵）；击穿=进程内 per-key 单飞 + Redis SETNX 跨实例重建锁（未抢到 200ms 重读 L2 再降级回源）；雪崩=L2 TTL 随机抖动；写路径 evict=删 L2 + 本地失效 + Pub/Sub 广播（丢消息由 L1 TTL 兜底）+ 延迟双删（防旧值回填）。Redis 异常 fail-open（跳过 L2 直读 DB）。
  - 位置：`common/cache/TwoLevelCacheService`、`LocalCacheInvalidator`、`CacheKeys`、`CacheAdminController`（只读 stats）。
  - **红线**：仅展示/字典类读；资金、库存、订单状态**禁止**经此缓存（这些走 ADR-1 的 CAS 直读 DB）。
- **代价**：一致性窗口 = L1 TTL（Pub/Sub 丢消息时）；组件多（Caffeine + Redis + 频道）；看板这类聚合缓存与数据权限交叉，需要"受限身份绕缓存"特例。
- **何时该换**：要求强一致（写完立刻全局可见）时去掉 L1 或用 binlog 失效；键空间变大到本地内存吃紧时给 L1 加容量上限与权重淘汰（Caffeine 已支持，需要显式配置）。

## ADR-8 计费失败：硬失败阻塞 → 欠费化放行

- **背景**：还电时若超时费 > 余额，原设计让计费失败阻塞还电流程——用户还不掉电池、仓一直被占、订单卡死，越堵越收不到钱。
- **选项**：① 硬失败（还电不成功，等用户充值）；② 直接免单（资金漏洞）；③ **欠费化**：订单照常完成 + 生成欠费单 + 后续下单门槛拦截。
- **决策**：③。计费不足额 → 欠费单（同订单唯一，计费事务内累加）；TAKE/SWAP 有 OPEN 欠费**拒单**，RETURN **放行**（防逼停归还）；结清=余额补缴（CAS 扣款同事务）或客服减免 → 自动关 ORDER_ARREARS 告警 + 站内信。
  - 位置：`user/service/ArrearsService`、`SwapOrderService.create` 欠费门槛、`UserArrearsController`；对账组 `arrears-integrity`；`fixes/resilience-patch.md`（G1）。
- **代价**：形成应收挂账（需要催收/坏账口径）；风控上要防"恶意欠费换电"（靠下单门槛 + 押金 + 告警）；财务报表要区分已收/应收。
- **何时该换**：接入真实支付通道后可改为"还电时发起代扣/补扣"（欠费单变成待扣款单）；若坏账率超阈值，收紧为"欠费即限制 RETURN 之外的全部服务 + 人工介入"。

## ADR-9 管理端身份：账号体系 + 静态 token（break-glass）收编

- **背景**：管理端需要正常账号体系（角色/权限/审计），但也要有"账号体系或 DB 出问题时还能进得去"的应急通道。
- **选项**：① 只有账号密码（DB 挂了进不去，应急处置无从下手）；② 只有静态 token（无角色粒度、审计弱）；③ **双轨并存且收编在同一过滤器与权限码体系内**（token 是"应急身份"，仍受权限码与审计约束）。
- **决策**：③。BCrypt + JWT 账号体系为主；`X-Admin-Token` 静态 token 为 break-glass，**只经环境变量注入**（仓库零明文），未配置即启动失败（fail-fast，不允许空密钥放行）；token 身份同样走权限码校验与操作审计。历史教训：管理端 secret 曾出现在仓库/日志 → S5 修复 + 密钥轮换 + 红线（临时密钥文件用后即删）。
  - 位置：`admin/security/`（AdminAuthFilter/AdminContext/AdminSecrets）、`admin/config/AdminSecurityConfig`（securityMatcher 仅 `/admin/**`）、`document/knowledge/rbac-and-audit.md`。
- **代价**：两条身份路径要同时维护（权限码、审计、数据范围都要覆盖 token 身份）；token 泄露面需要轮换机制（env 注入 + 定期换）。
- **何时该换**：接入统一 IdP/OIDC 后，break-glass 收敛为"本地应急账号 + 一次性口令"；多租户化后 token 需要绑定租户维度。

## ADR-10 数据库演进：手工编号迁移脚本 vs Flyway/Liquibase

- **背景**：schema 从 S0 到 S7 持续演进（16 个脚本：建库/业务/支付/outbox/工单/电池健康/调拨/计费策略/agent/RBAC/结算/对账/用户服务/韧性补丁/数据范围）。
- **选项**：① Flyway/Liquibase（自动校验和、版本表、CI 可跑）；② **手工编号脚本**（`db/00-…`~`db/16-…`，幂等写法，人工按序执行 + 容器 initdb 自动执行）。
- **决策**：②（现阶段）。理由：脚本必须同时服务三种环境（本地手工、docker-compose initdb、云上手工），且大量脚本含 `DELIMITER` 存储过程/触发器——Flyway 对 MySQL 存储过程的分隔符处理需要额外配置；编号 + 幂等（`CREATE TABLE IF NOT EXISTS` / 条件 ALTER）已能保证可重复执行；P0-1 之后**集成测试用容器 initdb 全量执行这 16 个脚本**，等于给迁移链加了门禁（脚本坏了 CI 就红）。
  - 位置：`db/*.sql`、`it/AbstractContainersIT.copyDbScripts`（拷入 `/docker-entrypoint-initdb.d`）、`docker-compose.middleware.yml`。
- **代价**：没有版本表与校验和（"某环境执行到哪一版"靠人工记录/对账）；回滚脚本要自己写；跨环境漂移风险高于工具化方案。
- **何时该换**：环境数量增加（>3）或需要"自动校验各环境 schema 版本"时上 Flyway（把现有脚本作为 baseline，`DELIMITER` 段拆分为独立 migration 并配置分隔符）；出现频繁回滚需求时上 Liquibase（changelog 支持 rollback 段）。

---

## 白板 15 分钟讲述顺序（建议）

1. 一句话系统形态：单库单体 + 设备双通道（HTTP 降级 / MQ 终态）+ Redis（分配/锁/延迟/缓存）+ 14 组对账兜底。
2. 主链路画一遍：下单（资格+幂等+分配双层）→ 指令下发（commandSeq）→ 设备事件（双守卫）→ 订单 CAS 推进 → 计费（套餐/余额/欠费化）→ 对账。
3. 三个"为什么这么选"：ADR-2（双层分配）、ADR-6（分片保序演进，带 spike 实测反直觉结论）、ADR-8（欠费化）。
4. 多实例与可靠性：ADR-5（ZSET + 租约锁）、ADR-4（outbox）、ADR-1（CAS）、ADR-7（缓存 fail-open 与红线）。
5. 收尾：ADR-9/ADR-10 说明"知道边界在哪"（身份应急通道、迁移工具化的换轨时机）。
