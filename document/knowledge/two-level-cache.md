# 知识：两级缓存的标准形态（S3.8 WP1）

> 代码：`TwoLevelCacheService` / `LocalCacheInvalidator` / `CacheConfig` / `CacheKeys` / `CacheProperties`。
> 接入：套餐目录（`/user/plans`）、站点元数据（`/user/stations`，写路径 evict）。
> 证据：`scripts/verify/batch4/_c1_cache.ps1`（50 并发仅 1 次回源、evict 即时生效）。

## 1. 结构（标准 vs 最小）

| 关注点 | 最小实现（不做） | 标准实现（本 WP） |
|---|---|---|
| 层级 | 单层 Redis | Caffeine L1（本进程）+ Redis L2（跨实例） |
| 击穿 | 过期即全量回源 | 进程内 per-key 单飞 + Redis SETNX 跨实例重建锁（未抢到等待后重读，仍空才降级回源） |
| 雪崩 | 固定 TTL | L2 TTL 随机抖动（base + [0, base×10%]） |
| 穿透 | 无 | 空值哨兵（L1 Object 哨兵 / L2 JSON `{"nullValue":true}`，短 TTL）+ 入参校验 |
| 一致性 | 只设 TTL | Cache-Aside + 写路径 evict（L1 清 + L2 删 + Pub/Sub 广播）+ **延迟双删 500ms** |
| 降级 | 抛异常/雪崩 | Redis 故障读路径 fail-open（跳过 L2 直读 DB，L1 仍生效） |

## 2. 关键设计点

- **空值区分**：L2 用 JSON 信封（`{"nullValue":true}` 或 `{"data":...}`），否则"缓存了 null"与"未命中"不可分。
- **本地锁不删**：`localLocks` 常驻（键空间小），避免"删锁竞态"（A 删锁后 B/C 拿到不同锁对象并发回源）。
- **延迟双删原因**：删缓存后、DB 提交前，并发读可能把旧值回填 L2——二删兜底；二删只删 L2 不广播（L1 已清过）。
- **Pub/Sub 丢消息**：L1 TTL（30s）兜底；L2 由 TTL（300s+jitter）兜底。最终一致窗口 = min(L1 TTL, L2 TTL)。
- **指标**：`GET /admin/cache/stats`（l1Hits/l2Hits/`rebuild:<key>`/l1Size）——单飞验证与容量观察用。

## 3. 失败矩阵

| 故障 | 行为 | 一致性影响 |
|---|---|---|
| Redis 不可用 | 读跳过 L2（L1/DB）；写 evict 失败仅告警 | 窗口内可能读到旧 L1（≤30s），无正确性破坏 |
| 广播丢失 | 其他实例 L1 靠 TTL 过期 | ≤30s 脏读（展示数据可接受） |
| 重建锁实例宕机 | 锁 TTL 5s 自动释放 | 无 |
| L2 JSON 损坏 | 按未命中处理并回源重写 | 无 |
| 缓存禁用（配置） | 直读 DB | 无 |

## 4. 缓存红线（再次声明）

**禁止入缓存**：分配池/仓锁、钱包/押金、订单状态、指令流水、支付单。
**允许**：套餐/站点等字典与展示数据、看板聚合（秒级不一致可接受）。
理由：资金/库存的正确性依赖 DB 条件 UPDATE 与事务，缓存只会引入不可控窗口。

## 5. 已知边界（如实声明）

- 延迟双删是"窗口缩小"而非"消除"；极端时序下仍以 TTL 收敛（最终一致）。
- L1 广播为单频道全量键消息；键数量级大时应升级为"命名空间版本号"方案（roadmap 未做）。
- 仅单实例联调验证（无真双实例广播实证）；双实例行为由 `TwoLevelCacheServiceTest#广播联动对端` 单测覆盖。
