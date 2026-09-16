# Redis 数据回退的运行时一致性三连坑 + 恢复清单（P1-10 混沌发现）

> 状态：已修复（2026-09-16，批次19）| 证据：`_c21_out.txt` / `_c23_out.txt`

## 背景

Redis 强杀（无 save）后重启加载**旧 dump**，暴露了多个"启动期对齐"设计覆盖不到的**运行时回退**场景。

## 坑 1：DevSeeder 把用户手上的电池绑回仓（重启即半写）

- 现象：重启后对账 `cell→battery: cellId=4 batteryId=4 battery.cellId=null`。
- 根因：补全 `cell.battery_id` 的条件只查"cell 侧为空"，不校验电池是否在仓（`battery.cell_id`）。
  电池被取走后（battery.cell_id=null、holder=用户）重启 → 被硬绑回仓；`updateById` 回写旧
  update_time，掩盖了变更时刻（排查时靠时间戳对不上 vs 对账结果突变才定位）。
- 修复：补绑前校验 `battery.cell_id == cell.id`；新增反向自愈（仓引用种子电池但电池不在仓 → 解除引用）。
  重启日志：`种子自愈：解除错绑 cellNo=4 batteryNo=BAT-0004（电池不在仓）`。

## 坑 2：cmd-seq 运行时回退撞 uk_cabinet_seq

- 现象：恢复窗口内下单 500，日志 `Duplicate entry 'SWAP-C-001-2758' for key command_log.uk_cabinet_seq`。
- 根因：启动期有对齐（seedSeqFromDb），但"**运行中** Redis 回退"没有兜底——INCR 复用 DB 已有序号。
- 修复：`nextSeq` 改单脚本原子（GET 现值与 DB `MAX(command_seq)` 取大 +1 + SET）。
- 教训：**"跨重启持久"的计数器，兜底必须落在每次生成路径上，而不是启动路径上。**

## 坑 3：workerId 租约 owner=UUID，RDB 回退后持续 ERROR

- 现象：`[ID] workerId 租约续期失败（持有者不匹配/键丢失）…需人工介入` 持续报告（每 30s）。
- 根因：owner 为随机 UUID（每次启动变化）；RDB 回退后键 owner=旧实例 UUID → 续期校验失败
  （保守设计，跨机安全语义本身正确）。
- 修复：owner 改**稳定实例标识（ip:port）** + lease 夺回分支（仅夺本实例旧租约）；
  跨机/跨端口冲突语义不变（owner 不同仍拒绝）。

## Redis 数据丢失恢复清单（运维）

1. 按 runbook §1 命令重启 Redis（`--dir F:\Redis` 必须）；
2. `POST /admin/ops/rebuild-alloc`（分配池按 DB 真值重建）；
3. 跑一次对账（`POST /admin/reconcile/run`）确认 = 基线（本批实测：恢复后立即对账 0）；
4. 观察 `[ID]` 日志：新版 owner 稳定后自动续期/夺回（自愈）；老版本需删除租约键 + 重启；
5. 下发指令的序号：新版每次生成自带 DB 兜底（不会撞索引）；老版本需重启触发启动对齐。
