# 知识：outbox 本地消息表（S3.8 WP6）

> 代码：`OutboxService` / `OutboxRelayTask` / `OutboxPublisher` / `AlarmOutboxPublisher`；
> 表：`outbox_event`（db/04）；首批接入：告警创建/人工处理事件（topic `swap-alarm`）。

## 1. 解决什么问题

旧实现：`alarm` 落库后**同步直接发 MQ**；broker/网络故障时 `log.warn` 后事件丢失（Agent 侧永远收不到）。
outbox 把"发事件"变成**持久化意图**：业务事务内写 `outbox_event(NEW)`，中继任务异步补投 MQ。

```
[业务事务] insert alarm + insert outbox(NEW, event_key)  ← 同提交/回滚
[中继 5s]  SELECT NEW WHERE next_retry<=now
           发布成功 → SENT（sent_time）
           发布失败 → attempts+1，退避 next_retry = now + base×N
           超限(20) → DEAD + OUTBOX_DEAD 告警
```

## 2. 关键设计

| 点 | 做法 | 理由 |
|---|---|---|
| 幂等 | `event_key` 唯一（如 `alarm:{id}:RAISED`） | 重复投递/重放不产生第二条事件 |
| 事务性 | enqueue 与业务写同事务 | 业务成功但事件没落库=丢；落库但业务回滚=幽灵事件，都避免 |
| 顺序/竞争 | 中继按 id 升序；状态推进全部 CAS（`WHERE status=NEW`） | 多实例/重入不重复推进 |
| 多实例 | 中继套 `JobLockService` 租约锁 | 同一时刻仅一个实例投递 |
| 退避 | base×attempts（5s 递增），上限 20 次 | 短时抖动不误死信；真长时间故障也最终可见（DEAD+告警） |
| 发布超时 | `sendAsync().get(3s)` | send 无上限会卡死中继线程（队列积压） |
| 死信 | `status=DEAD` + `OUTBOX_DEAD` 告警 | 人工可查 payload 重放（运维入口） |

## 3. 投递语义（如实声明）

- **at-least-once**：极端情况下（发布成功但 markSent 前宕机）事件可能重投 → 消费端必须幂等（Agent 侧按 alarmId+eventKind 去重）；
- 自动恢复（`markRecovered`）目前不发事件（历史行为保持）；如需审计扩展，加 `RECOVERED` 事件即可（同机制）。

## 4. 与"消息事务"方案对比（答辩用）

| 方案 | 取舍 |
|---|---|
| 2PC/XA | 强一致但资源锁长、MQ 支持差——不做 |
| TCC | 需要业务改造三个接口 + 幂等/悬挂处理，收益与复杂度不成比——不做 |
| Seata AT | 引入中间件+全局锁，本项目单体同库——不必要 |
| **本地消息表（本实现）** | 同库事务 + 异步补投 + 对账兜底：单体最务实，故障不丢、最终一致 |

## 5. 证据与边界

- `scripts/verify/batch4/_c4_outbox.ps1`：停 MQ → 两条重复告警只落 1 条 outbox（去重）且 NEW+attempts>0；
  起 MQ → SENT、无死信。**实测 PASS**。
- 边界：中继与告警写在同一 MySQL；跨库/跨服务场景需要 Debezium/CDC 或独立中继（未做）；DEAD 人工重放接口未提供（DB 直接改状态或后续加管理端点）。
