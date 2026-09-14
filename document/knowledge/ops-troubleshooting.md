# 运维手册：慢 SQL 与边界极限故障预案（S5 运维审查）

> 定位：面试/交接用运维材料。上半部分=慢 SQL 治理（本次审查的实际证据与处置）；
> 下半部分=边界极限故障场景预案（症状/系统已有自动防护/人工处置/恢复后动作）。
> 与 `runbook.md`（日常运行）互补；本档只管"出了事怎么办"。

## 一、慢 SQL 治理

### 1.1 审查结论（2026-09-14，逐查询核对索引）

| 热路径查询 | 频率 | 审查前状态 | 处置 |
|---|---|---|---|
| `OrderEventService.findBySeq`（设备事件推进订单：`cabinet_id+open_command_seq+status`） | 每个门开/取电/还电事件 | **无索引 → 全表扫**（订单表随业务增长） | db/10 加 `idx_open_command_seq(cabinet_id, open_command_seq)` |
| `SwapOrderService.findByOpenCommand`（对账定位） | 对账批 | 同上 | 同上 |
| `OrderSweepTask`（`status=? AND xxx_time < deadline`） | **每 5s** | 无 status 索引 → 每 5s 全表扫（LIMIT 不阻止扫描） | db/10 加 `idx_order_status(status)` |
| `ReconcileService.checkStaleActive` | 日终 | 同上 | 同上 |
| `pageOrders` 管理分页 `ORDER BY create_time DESC`（无 userId 过滤） | 管理端 | filesort 全表排序 | db/10 加 `idx_order_create_time(create_time)` |
| `AlarmService.list`（未处理倒序） | 管理端 | `idx_device_type_time` 前缀不匹配 | db/10 加 `idx_alarm_handled_time(handled, create_time)` |
| 指令超时扫描 `findTimeoutPending` | 15s | `idx_status_time(command_status, create_time)` 已覆盖 | 无需动 |
| outbox 中继 / 延迟任务 / 工单 / 调拨 / 建议单扫描 | 5s~30s | 均已有 `(status, time)` 型索引 | 无需动 |

迁移：`db/10-indexes.sql`（幂等，information_schema 检查后 ADD）；本地已应用并 `SHOW INDEX` 验证。

### 1.2 慢 SQL 排查标准流程

1. **定位**：`SHOW FULL PROCESSLIST`（Running 超 3s）→ `information_schema.innodb_trx`（锁等待）→ 慢日志
   （云上建议开 `slow_query_log`，阈值 2s，落盘 `/var/lib/mysql/slow.log`）。
2. **EXPLAIN**：看 `type`（ALL=全表扫必改）、`key`（NULL=未用索引）、`rows`（数量级）。
3. **处置分层**：
   - 缺索引 → db/10 模式幂等迁移（生产半夜窗口，`pt-online-schema-change` 或低峰直加）；
   - 语义可加 LIMIT 却未加 → 代码层限量（本项目扫描任务已全量 LIMIT，见审计）；
   - N+1 → 批量/合并（本项目已知 N+1 面：看板/站点列表按站点循环查柜，站点数<100 且
     看板有 60s 缓存——如实声明，超过百站需改 JOIN/预聚合）；
   - 长事务 → 事务边界复查（本项目设计原则：短事务，HTTP/Redis 不进事务，仅告警治理例外，见 2.5）。

### 1.3 已知慢查询面（如实声明，暂不优化）

- `listUserStations`/`DashboardService.compute`：按站点循环查柜（N+1），站点规模小 + 看板缓存 60s 兜底；
- `BatteryHealthScanTask`：按 SOH 范围扫（无 soh 索引），1 小时一次、LIMIT 200；fast 联调模式 3s 一次属开发态；
- `OfflineScanTask`：全柜扫（柜数=几十），30s 一次，量级无风险。

## 二、边界极限故障场景预案

格式：**症状 → 系统自动防护（已实现）→ 人工处置 → 恢复后动作**。

### 2.1 Redis 全挂（或重启丢数据）

- 症状：下单报"指令序号生成失败"；心跳 SET 失败；分配 SPOP 失败。
- 自动防护：指令 seq INCR 不可用 → **快速失败不发重**（CommandLogService.nextSeq）；分配失败 → 下单事务回滚
  （未落单，用户可重试）；延迟任务登记失败 → 扫描任务兜底（订单扫把/对账）；JobLock 拿不到锁 → 降级直接执行
  （扫描任务幂等）；代际守卫 fail-open。
- 人工处置：确认设备实际在线（心跳 TTL 丢失会误判离线 → **offline-scan 可能批量 OFFLINE 告警**，先别信）；
  `systemctl restart redis`。
- 恢复后动作：**必须重建分配集合**（Redis 集合全空）——已提供管理端点
  `POST /admin/ops/rebuild-alloc`（AdminToken 保护，S5 运维审查补；幂等，锁定中/不可用柜不入池）。
  指令 seq 由启动对齐脚本从 DB MAX 恢复，无需人工。

### 2.2 MySQL 挂 / 重启

- 症状：全部接口 500；健康检查 DOWN。
- 自动防护：systemd `Restart=on-failure` 会重启应用（无效但无害）；DB 恢复后无脏状态——所有写路径
  短事务 + 条件 UPDATE/唯一键，事件/指令由幂等与对账收敛。
- 人工处置：`SHOW PROCESSLIST` 查锁源；`innodb_trx` 杀长事务；恢复连接池（默认自愈）。
- 恢复后动作：看对账日报（差异应归零）；命令流水中 PENDING 由对账自动收敛。

### 2.3 RocketMQ broker/proxy 挂

- 症状：平台日志 `[MQ事件消费] consumer 构建失败…5s 后重试`；sim 日志发送退避。
- 自动防护：平台消费线程 5s 节拍懒重建；**HTTP 通道恒开**（事件直传不受影响，心跳恒 HTTP）；
  sim `MqEventReporter` 单线程队首持留 + 快速退避 1~5s×5 → 持续重试，**保序不跳**；
  dual 模式双通道幂等去重；仅队列满才丢最旧（QUERY_STATE/对账兜底）。
- 人工处置：先查 **proxy 8081**（只起 broker 不起 proxy 是静默故障）；`ss -ltn | grep 8081`。
- 恢复后动作：队列积压消息按序回放，平台序守卫幂等吸收；确认消费位点追平。

### 2.4 设备/事件风暴（模拟器或真实柜异常高频上报）

- 症状：CPU 高、DB QPS 尖峰、告警风暴。
- 自动防护：事件序守卫幂等丢弃（重放成本=一次条件 UPDATE）；指令单轮对账限量；告警
  **类型级限速 + 去重窗口 + DB 时间窗兜底**；批量离线合并单条告警；工单 alarm_id 唯一防重复开单。
- 人工处置：按 cabinetNo 定位风暴源，故障注入开关/断连处置；必要时临时 `swap.ratelimit.enabled=true`。
- 恢复后动作：对账日报确认无差异；清理重复告警（markRecovered 由事件恢复自动关）。

### 2.5 长事务/死锁

- 症状：`Deadlock found`（1213）日志、锁等待超时（1205）。
- 自动防护：退款路径 `DeadlockRetryExecutor` 自动重试（动作幂等）；所有资金动作唯一键闸幂等；
  设计原则=短事务（HTTP/Redis 不进事务；告警 raise 的 Redis 去重在事务内是唯一例外，已评估低风险）。
- 人工处置：`innodb_trx` 定位持锁方 → 杀线程；复现频发时检查索引缺失（2.2 对账批与 2.4 事件流并发面）。
- 恢复后动作：重试成功的动作由唯一键保证不重复入账，核对 payment_record 流水。

### 2.6 时钟回拨 / 雪花 ID

- 症状：ID 生成抛 `ClockMovedBackwardsException`。
- 自动防护：workerId 由 Redis 预约（-1 自动编号 0~31，跨实例唯一）；时钟回拨明确抛异常拒绝生成
  （不静默重复）；指令 seq 启动对齐 DB MAX。
- 人工处置：chrony 校时后重启应用；确认 workerId 未冲突（Redis `worker-id` 键）。

### 2.7 内存/GC 尖峰（云上 3.6Gi 预算）

- 症状：GC 日志暂停时间放大（`-Xlog:gc` 画像）、OOM 前 full GC 频繁。
- 自动防护：线程池有界、sim 上报队列有界、批处理限量、两级缓存 L1 有界（l1MaxSize）。
- 人工处置：`jcmd <pid> GC.heap_dump` → MAT 分析；先用 S5.3 实测基线对照（512m 定堆 465.5/s、p99 GC 9.9ms）；
  云上堆建议 768m~1G（见部署锚点）。
- 恢复后动作：回放负载验证 GC 画像回基线。

### 2.8 磁盘（DB/日志）

- 症状：磁盘写满 → MySQL 拒绝写入。
- 人工处置：`df -h`；清应用滚动日志；**勿删 binlog 前先确认备份**。
- 恢复后动作：备份 cron（云上部署后按 reason 时代模式恢复：每日 02:30 mysqldump+rdb 落盘）。

## 三、压测/演练覆盖声明（诚实口径）

- 已实测：读路径容量（465.5/s）、GC 画像（512m 定堆）、剧本 batch1-7 全绿、优雅停机。
- 未实测：写路径（下单/事件）容量压测、Redis/MySQL/MQ 逐个挂掉的注入演练（预案为**推导**，
  标注于各节"自动防护"来自代码事实、"人工处置"为未演练步骤）。
- 下一步演练清单：① Redis 挂→重启→rebuild 分配集合（依赖 2.1 roadmap 的重建入口）；
  ② MQ 挂 10min 恢复追平；③ 死锁注入；④ 云上 3Mbps 带宽下的真实设备吞吐（走内网压测）。
