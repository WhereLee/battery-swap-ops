# 知识：Redis 运维基线（S3.8 WP8）

> 剧本：`scripts/verify/batch4/_c6_redis_ops.ps1`（PING/持久化/内存/大 key/慢日志/热 key 探测）。
> 本项目 Redis 用途：在线态 TTL、限流桶、延迟队列、租约锁、缓存 L2、去重/代际集合。

## 1. 持久化（本项目选择与理由）

| 方案 | 特点 | 取舍 |
|---|---|---|
| RDB（`save` 快照） | 体积小、恢复快；可能丢最后一次快照后的数据 | **本项目采用**（缓存/限流可重建；锁/租约 TTL 自愈；延迟任务由扫描兜底） |
| AOF（everysec） | 丢数据窗口 ≤1s；文件大、恢复慢 | 不启用（真资金不落 Redis，Redis 不是事实源） |

**红线**：Redis 在本项目**从不作为唯一事实源**——订单/支付/告警都在 MySQL，Redis 只做加速与协调。
因此持久化策略可以接受"丢几秒"，这是有意的架构取舍，而不是遗漏。

## 2. 已踩坑（历史，已落档）

- **MISCONF 全写失败**：RDB 落盘失败 + `stop-writes-on-bgsave-error=yes` 时所有写命令拒绝；
  本项目要求 Redis 以 `--dir F:\Redis`（可写目录）启动。本地联调脚本已固化该启动方式。
- 日志文件被进程持有无法读取 → Windows 用 `FileShare.ReadWrite` 共享读。

## 3. 巡检基线（脚本覆盖）

| 项 | 命令 | 期望 |
|---|---|---|
| 连通 | `PING` | PONG |
| RDB 健康 | `INFO persistence` → `rdb_last_bgsave_status` | ok |
| 内存 | `INFO memory` + `CONFIG GET maxmemory-policy` | 有快照记录；策略明确（默认 noeviction，靠 TTL 控量） |
| 大 key | `--bigkeys`（生产用 `SCAN`+`MEMORY USAGE` 抽样，避免阻塞） | 无异常大 key |
| 慢日志 | `SLOWLOG LEN` / `SLOWLOG GET` | 有阈值记录可查 |
| 热 key | `--hotkeys`（需 LFU 策略） | 未启用 LFU 时如实标注，不伪造 |

## 4. 生产化建议（未做，roadmap）

- 监控：`used_memory`、`evicted_keys`、`keyspace_hits/misses`、慢日志条数 → Prometheus（S5）；
- 大 key 在线扫描必须用 `SCAN` 分批（本脚本数据量小用 `--bigkeys` 一次完成，生产不宜）；
- 热 key 用 LFU + 客户端本地缓存（L1 已具备）+ 读写分离；
- 容量：`maxmemory` + `allkeys-lru`（缓存用途）与"不淘汰"用途分实例的取舍需按部署形态定（单实例本地开发暂不拆分）。

## 5. 证据

`_c6_out.txt`：PING / RDB ok / 内存快照 / `swap:*` key 数量 / bigkeys 完成 / slowlog 长度。
