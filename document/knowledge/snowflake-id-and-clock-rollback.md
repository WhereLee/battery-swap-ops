# 知识：Snowflake ID 与时钟回拨（S3.8 WP5）

> 代码：`SnowflakeIdGenerator` / `WorkerIdRegistry`；配置 `swap.id.*`。
> 结果：两类单号已替换——订单 `SW{id}`、支付单 `R{id}`、退款单 `RF{id}`、流水 `PAY{id}`。

## 1. 位分配与容量

```
1 符号 | 41 时间戳(ms, epoch=2026-01-01) | 5 datacenterId | 5 workerId | 12 序列
```

- 单 worker 每毫秒 4096 个（12 位序列）；同毫秒耗尽则自旋等下一毫秒（不借未来、不重复）。
- 时间跨度 2^41 ms ≈ 69 年；epoch 自定义把剩余年限从 2026 算起。
- 32 datacenter × 32 worker = 1024 发号节点；本项目 `datacenter-id=1`，workerId 走租约。

## 2. 时钟回拨策略（核心取舍）

| 回拨幅度 | 行为 | 理由 |
|---|---|---|
| ≤ 5ms（默认容忍窗口） | 自旋等待追平后继续 | NTP 微调常态，业务无感 |
| > 5ms | 抛 `ClockMovedBackwardsException`，拒绝发号 | 宁可失败可见，不可生成重复/倒序 ID |

对比"借未来时间"（把回拨后的时间拨到 lastTimestamp 之后）：能继续发号，但时间戳不再单调于真实时间，
排查/排序会失真。本项目选择**拒绝 + 告警**（`log.error`），由调用方/看护升级。

## 3. workerId 多实例租约（标准姿态）

- `SET NX swap:id:worker:{0..31}` + owner token + TTL 120s；启动时取第一个空闲，全满则启动失败。
- 30s 节拍 LUA 比对持有者续期；**续期失败只 error 告警**——这是明确的降级窗口：
  TTL 到期后该 workerId 可能被其他实例拿走，存在跨实例重复 ID 风险（已落 `pitfalls` 记录）。
  更严格形态（Leaf/美团）会停机自保；本项目单机联调为主，选择"告警不中断"。
- 释放：@PreDestroy LUA 比对删除；启动失败不再预留租约（lease 从生成器装配时发起）。

## 4. 实测证据（2026-09-11）

- 双实例同 Redis：各占一个 workerId（`swap:id:worker:0`、`swap:id:worker:1`），互不冲突；
- 充值单号实测 `R91855916051333120`（`R` + 17 位数字，单调）；
- 订单号实测受阻：本地柜台电池已被此前剧本消耗殆尽（分配无资源），
  单号生成路径由 `SwapOrderServiceTest` 覆盖（`SW` 前缀断言），如实声明。

## 5. 为什么不继续用"时间戳+UUID 片段"

- 旧单号 `SW{millis}{uuid6}`：可读但**时钟回拨会倒序**、随机后缀索引局部性差；
- UUID/雪花取舍：雪花有序、位数短、可按时间范围查询；代价是依赖时钟与 workerId 管理。
- 备选标准差：UUIDv7（时间有序、无中心分配）——若未来去中心化需求上升可平滑替换（接口只暴露 `nextIdString()`）。
