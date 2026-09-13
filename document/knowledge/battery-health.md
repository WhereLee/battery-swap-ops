# 知识：电池健康与循环计数口径（S4.1）

> 代码：`BatteryCycleService` / `BatteryHealthScanTask` / `AdminBatteryController#health`；表：`battery_cycle_log`（db/06）。
> 证据：`scripts/verify/batch6/_c12_battery_health.ps1`（TAKE/RETURN 计数+流水、SOH 告警/恢复、对账零差异）。

## 1. 计数口径（先把定义钉死，能扛追问）

| 字段 | 定义 | 来源事件 |
|---|---|---|
| `battery.swaps` | 电池被**取出**的次数（换电服务次数） | `BATTERY_OUT` |
| `battery.cycle_count` | 电池**归仓**次数（放电→回收充电的"服务循环"） | `BATTERY_IN` |

**明确边界（面试会问的点）**：
- `cycle_count` 是**服务循环**口径（一次归还=1），**不是电池行业的"等效循环"**；
  等效循环需按 DOD/Ah 折算（`等效循环 += 放电深度/100`），依赖 BMS 明细（协议当前只有事件时刻 SOC），
  留作协议扩展后的演进项——**不假装精确**。
- 数据可信度来自"**流水是事实、计数器是派生**"（见下），而不是靠计数器自身可信。

## 2. 为什么先落流水再累加（可审计 + 幂等）

```
事件(BATTERY_OUT/IN)
  → battery_cycle_log 插入（UNIQUE (boot_id, event_seq)）
      ├─ 成功 → battery 计数原子 +1（setSql 自增）
      └─ 唯一键冲突（重投/重放）→ 忽略，不计数
```

- 幂等双保险：设备事件已有序守卫/代际守卫；流水唯一键是**最后一道**（跨守卫漏洞也不会双计）；
- 可审计：任何计数都能回放到流水（谁、何时、哪次事件、SOC 多少）；
- 对账：日终不变量 ⑦「`swaps`=OUT 流水数 且 `cycle_count`=IN 流水数」，不一致=计漏/计重/人工改数未留痕 → 告警交人工；
- 性能：每次事件 1 INSERT + 1 UPDATE（同事务），可接受；流水表按 (battery_no,id) 索引，健康档案取最近 20 条。

## 3. SOH 与健康分级

- SOH 由设备/BMS 上报（协议暂未含，联调由管理端维护；管理端可编辑）；
- 分级（配置化）：`GOOD ≥90 / FAIR ≥80 / POOR <80`；
- `BatteryHealthScanTask`（租约锁 + 看护心跳）：低于 `soh-warn-threshold` 产生 `BATTERY_HEALTH_LOW` 告警，
  回升自动关；告警 →（S4.4）工单闭环 → 维修/退役（既有 `updateBatteryStatus` 4/5）。

## 4. 与其它子系统的关系

- **工单**：健康告警可一键转工单（`/admin/work-order/from-alarm/{id}`），SLA 分级 HIGH/MEDIUM；
- **调拨（S4.2）**：`swaps/soh` 将作为调拨优先级输入（高损耗电池回站检修）；
- **看板（S4.5）**：可扩展"平均健康度"等指标（当前看板未含，roadmap）。

## 5. 边界（如实声明）

- 流水表长期增长：未做归档/分区（S5 运维阶段按量级评估）；
- 计数不含"取出时电量"对循环的加权（见 §1 的等效循环说明）；
- 扫描为全表按 soh 过滤（有索引? 未建 soh 索引——数据量小；量产需加索引/增量扫描，roadmap）。
