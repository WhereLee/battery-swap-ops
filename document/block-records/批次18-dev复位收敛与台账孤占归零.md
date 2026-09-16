# 批次18：dev 复位收敛修复 + 台账孤占归零 + MQ 环境整备（2026-09-16）

> 范围：批次17 遗留的两个 follow-up（reset 不收敛、T2 孤儿柜）+ 两个环境异常（心跳配置冲突、
> MQ 磁盘满）一次收口。用户拍板"都按推荐处理"（含 reset 修复 + T2 退役 + 环境对齐）。

## 0. 摘要

| 项 | 结果 |
|---|---|
| dev reset 收敛缺陷 | **两阶段重构**（清场→绑定）+ 种子缺失入报告；单测 4 例 |
| 防回归剧本 | `batch18` 交错引用图 → **PASS 11/11**（旧实现该图会残留 2 处悬挂） |
| T2 孤儿柜（SWAP-T2-224621） | **退役清理**（盘点 0 引用 → 备份 → 清 6 项 + Redis 2 键） |
| 对账 | **18 → 0 并保持**（reset/剧本/重启后复验均 0） |
| 心跳配置冲突 | load 档超时 5s → **30s**（> sim 心跳 10s）；BATCH_OFFLINE 震荡停止 |
| MQ 磁盘满（50001） | store 迁 F 盘（`storePathRootDir`）+ 回收 2.1GB；topic 重建；投递恢复 |
| 全量验证 | `mvn clean verify`：contract 4 + server 344 + sim 30 = **378/378 绿**（覆盖率门禁过） |
| 剧本回归 | `_c0` GATE-DEV-RESET PASS（幂等 + E2E） |

## 1. dev reset 两阶段重构（代码）

**改动**：`DevResetService.reset()`（swap-server）
- 阶段一"清场"：① 全部电池一次 update 脱仓（cell_id/holder=null、回 CHARGING）；② 逐柜逐仓清引用
  （battery_id/lock_order_id=null、EMPTY、删 Redis 仓锁），同时把 cell 收入 `cellIndex` 供阶段二复用
  （Pass2 不再重复查仓）。
- 阶段二"绑定"：按种子定义（`BAT-{i*cellsPerCabinet+j}`）逐仓重绑——种子电池 FULL/soc=100/cell_id 回写；
  种子缺失 → 保持空仓 + `seedMissing` 列表 + WARN（替代原 `continue` 静默跳过）。
- 报告字段：`extrasParked` → `batteriesDetached`（全量清场数）+ 新增 `seedMissing`。
- 删除 `detachBattery()`（被全量清场取代）、`seededBatteryIds` 过滤段（不再需要）。

**单测**（`DevResetServiceTest`，4 例）：主流程（occupied/seedMissing 空）；**清场先于绑定的顺序
不变式**（InOrder：全量电池脱仓 → 12 仓扫描恰好 12 次；update 次数结构 14=12+2、3=1+2）；
种子缺失入报告（仓清空 + 2 条 seedMissing）；再执行幂等。

## 2. T2 孤儿柜退役（数据）

- **盘点**（清理前）：订单 0 / command_log 0 / work_order 0 引用；需处置 6 项——
  alarm 127 条（OFFLINE 每 ~5 分钟新增）、outbox 127 条、transfer_task 1 + item 2（09-13）、
  cell 12（137-148）、cabinet 1（id=14）。
- **备份**：`diag-archive/t2-retire-20260916/`（6 个 mysqldump `-r` 文件；工作区根，不入 git）。
- **清理**：outbox → transfer item/task → alarm → cell → cabinet（子先父后）+ Redis 残留键
  `swap:alloc:empty:*`、`swap:alarm:dedup:OFFLINE:*`。
- **验证**：6 项计数全 0；对账 18→2（仅余 transfer，因 T2 的 cell 悬挂已被新 reset 清场清除）→
  退役后 **total=0**。

## 3. 防回归剧本 batch18

- `scripts/verify/batch18/_b18_reset_convergence.ps1`（证据 `_b18_out.txt`，**PASS 11/11**）：
  ① 干净基线 reset（occupied=cabinets×6、seedMissing 空、对账 0）；② 构造"一致但错位"的 4-环交错
  引用图（cell1↔b6、cell6↔b4、cell4↔b5、cell5↔b1；cell2/3 正常）——图本身对账 0；
  ③ reset 后 C-001 精确归位：`1=BAT-0001..6=BAT-0006` 且反向 `1=1..6=6`；④ 对账 0；⑤ 再 reset 幂等。
- **旧实现的 FAIL 点**（设计说明）：该图下旧逻辑 j=5 会 detach 掉 j=1 刚绑的 b1、j=6 打掉 j=4 的 b4，
  残留 2 处悬挂——本剧本即该缺陷的回归防线。
- `_c0` 同步：Log 字段 `extrasParked` → `batteriesDetached`/`seedMissing`；回归 PASS。

## 4. 环境整备（MQ + 心跳）

**① 心跳配置冲突**：load 档 `heartbeat-timeout-seconds=5` < sim 心跳 10s → BATCH_OFFLINE 每 10s 震荡
（3195 条历史 BATCH 告警）。改 `.local/run-server-load.bat` → **30s**（bat 同步补 `mq.enabled=false`
与运行实例一致）→ 重启平台 → 近 10/20 分钟窗口 BATCH_OFFLINE **= 0**（修复生效）。

**② MQ 磁盘满**：C 盘 91% → broker 拒写（`50001 disk full`）→ 投递失败转死信（OUTBOX_DEAD）。
处置：`broker.conf` 加 `storePathRootDir = F:/RocketMQ/store` → 停 broker/proxy → 删旧 store（2.1GB，
C 盘 free 21.8→24.0GB）→ 起 broker → 起 proxy（**注意**：proxy 需 bat 方式启动，Start-Process 直起
mqproxy.cmd 静默失败）→ 重建 topic（`swap-device-event`、`swap-alarm`，`+message.type=NORMAL`）。
**三连坑与 40014 细节**：见 `pitfalls/mq-store-rebuild-topic-recovery.md`（含 proxy
`enableTopicMessageTypeCheck=false` 的 dev 口径与诚实边界）。

**③ 投递恢复验证**：outbox `SENT 385→388+` 持续增长、`NEW` 清零；`swap-alarm` topic offset>0；
近 20 分钟告警仅 OUTBOX_DEAD=3（磁盘满窗口尾声），**RECONCILE_ERROR 无新增**（自动对账归 0）。

## 5. 产物与证据

- **代码**：`DevResetService`（两阶段）+ `DevResetServiceTest`（4 例）。
- **剧本**：`scripts/verify/batch18/`（脚本 + `_b18_out.txt`）；`scripts/verify/batch4/_c0_dev_reset.ps1`（Log 同步）。
- **备份**（不入 git）：`diag-archive/t2-retire-20260916/`（T2 域 6 个 dump）。
- **环境**（不入 git）：`.local/run-server-load.bat`（心跳 30s）；`broker.conf`（storePathRootDir）；
  `proxy-dev.json`（enableTopicMessageTypeCheck=false）；`.local/_start-proxy.bat`（proxy 启动运维件）。
- **文档**：本记录；`pitfalls/dev-reset-nonconvergent-orphans.md`（重开→闭合）；
  `pitfalls/mq-store-rebuild-topic-recovery.md`（新）。

## 6. 遗留与说明

- 40014 的根因仅定位到"proxy 的 ClusterMetadataService 缓存通道判 UNSPECIFIED"（remoting 对照正常、
  重建前长期投递正常——两点矛盾如实记录）；dev 以兼容开关绕过，**未彻底定位**，生产级结论应单独立项。
- outbox `DEAD=3250` 为磁盘满窗口的历史死信（保留审计轨迹，不清理）；其 OUTBOX_DEAD 告警亦为历史噪音。
- "7 vs 18"两时点快照差异随残留清零失去意义（不再追因）。
- `.local` 下批次18 期间的临时诊断脚本（`_t2_*.ps1`、`_mq_verify.ps1`、`_recon_check.ps1` 等）用后即删；
  仅保留 `_start-proxy.bat`（proxy 启动运维件，gitignored）。
