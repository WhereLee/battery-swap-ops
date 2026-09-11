# 知识：工单闭环与 SLA（S4.4）

> 代码：`WorkOrderService` / `WorkOrderSlaHandler` / `AdminWorkOrderController`；表：`work_order`、`work_order_log`（db/05）。
> 证据：`scripts/verify/batch5/_c7_workorder.ps1`（全生命周期 + SLA 超时告警 + 审计 + 幂等 + 非法流转拒绝）。

## 1. 状态机（只前向，全 CAS）

```
OPEN(1) --triage--> TRIAGED(2) --assign--> ASSIGNED(3) --start--> HANDLING(4) --verify--> VERIFIED(5) --close--> CLOSED(6)
```

- 每个迁移 = `UPDATE ... WHERE id=? AND status=?`（CAS），未命中即"当前状态不允许该操作"（清晰报错，不猜测）；
- 每次流转写 `work_order_log`（动作/前后状态/操作人/备注）——审计闭环可追。

## 2. 幂等与唯一性

- `alarm_id` 唯一键：一个告警最多一张工单；并发重复转换由唯一键兜底，捕获 `DuplicateKey` 后重读返回既有；
- 重复点"转单"= 返回同一张工单，不产生第二张。

## 3. SLA 设计

| 严重级 | 默认 SLA | 说明 |
|---|---|---|
| HIGH | 30min | 柜故障/对账差异/outbox 死信 |
| MEDIUM | 120min | 离线/批量离线/仓故障 |
| LOW | 480min | 其余（默认） |

- 创建时算 `sla_deadline` 并投延迟队列（taskId=woNo）；
- 到点处理器：仅活跃态且未置位才置 `sla_breached=1`（CAS 幂等），随后 raise `WORK_ORDER_SLA_BREACH` 告警；
- `verify/close` 时取消延迟任务（终态判定保证空转无害）；
- 登记失败只告警不阻断（与既有降级哲学一致）。

## 4. 与既有资产的复用（S3.8 → S4.4）

- 延迟队列：SLA 定时（S3.3）；
- 告警治理：转单来源 + 超时升级告警（S3.6）；
- Snowflake：`woNo = WO{id}`（WP5）；
- 分页/审计样式：沿用 order/alarm 域惯例。

## 5. 边界（如实声明）

- 转单目前是**人工触发**（管理端从告警转单）；"按类型自动转单 + 智能分诊"未做（S4.6/Agent 方向）；
- `operator` 暂固定 "system"/"admin"（管理端没有用户体系，S4 RBAC 后再接真实操作人）；
- SLA 到点只升级一次（不重复催办；分级催办/升级链路未做）；
- 剧本使用 fast 模式 `sla-minutes-high=0` 验证超时路径（生产默认 30 分钟）。
