# 知识：站间调拨（S4.2）

> 代码：`TransferService` / `AdminTransferController`；表：`transfer_task`/`transfer_task_item`（db/07），站点增经纬度。
> 证据：`scripts/verify/batch6/_c13_transfer.ps1`（建议→创建→审批→出库→入库→DONE→对账零差异→复位）。

## 1. 供需规则（启发式，可解释、不做运筹优化）

- 数据源：**分配池**（与用户端"能不能换到"同源）统计各站满电数；
- 富余：`full - surplusKeepLevel > 0`（默认保留 4 颗才外调，防搬空本站）；
- 缺口：`full < deficitTargetLevel`（默认 3 颗为补充目标）；
- 数量：`min(缺口, 富余, maxPerTask=3)`；
- 配对：缺口站按缺口升序（最饿优先），选**Haversine 最近**富余站；坐标缺失→`distanceUnknown=true` 排最后，**不伪造距离**；
- 明确不做：多跳/车辆路径/成本最优/时间窗（文档声明，避免"为了算法而算法"）。

## 2. 状态机与聚合推进

```
DRAFT(1) --approve--> APPROVED(2) --首次出库--> EXECUTING(3) --明细全 IN--> DONE(4)
DRAFT/APPROVED --cancel--> CANCELLED(5)；EXECUTING 不可取消（在途资产必须闭环）
```

- **任务状态由明细聚合**（不是人工点"完成"）：`COUNT(明细≠IN)=0` → DONE；
- 明细：`PENDING→OUT→IN`，全部 CAS（重复请求显式拒绝，不猜）；
- 出库守卫：明细 PENDING + 电池在**调出站**仓内 + 无持有人；清仓、电池置在途（`cellId=null`，status 复用 CHARGING，见边界）；
- 入库守卫：明细 OUT + 目标仓属**调入站**且空；入仓后电池状态按 SOC 判 FULL/CHARGING；
- 任何结构变更后 `rebuildFromDb()` 重建分配池。

## 3. 对账不变量 ⑧（transfer-ledger）

- `OUT 未 IN` 的电池必须"不在仓且无持有人"；
- `IN` 的电池必须身处明细记录的 `in_cell_id`；
- 任务状态与明细聚合一致（EXECUTING 至少 1 OUT 且未全 IN；DONE 必须全 IN）。
差异=绕过流程改数 → 告警人工（与 counter 不变量同一哲学：**派生状态必须可对账到事实**）。

## 4. 边界（如实声明）

- 执行=**人工确认台账**（管理员点出库/入库）；"设备事件驱动调拨"需协议增加调拨动作，roadmap；
- 在途电池复用 `CHARGING + cellId=null`（未在契约新增 TRANSIT 状态码——避免为单一场景扩枚举；语义在文档声明）；
- 单级审批（approve 一人）；无运力/司机/时间窗管理；
- 建议为实时计算（只读不落库），未做建议快照/采纳率统计。
