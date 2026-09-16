# 批次17 —— P0-3d/e 写路径容量与 JVM 排障故事

> 日期：2026-09-16
> 范围：roadmap P0-3 的后两项（d 写路径容量 / e JVM 故事）+ 证据重跑收口。
> 前置：批次16（a/b/c 读路径/资源画像/量级换算，已提交 `57e1d69`）。
> 交接背景：`2026-09-16-1856-写路径容量模块-交接文档.md`（主体先完成、最终证据被中断轮覆盖）；本批次完成其 §7 执行序 1-4：重跑证据 → 全量测试 → 提交 → 本文档。

## 1. 结论（先给数字）

### 1.1 写路径（TAKE+RETURN 循环，19:03-19:11 最终证据轮）

| 指标 | steady-w20 | steady-w20-repeat |
|---|---|---|
| 样本 / 错误 | 1,140 / **0** | 1,140 / **0** |
| 完成订单 | 320（TAKE+RETURN 各 160） | 320 |
| avg / p95 / p99 / max | 16 / 30 / 47 / 233 ms | 16 / 29 / 43 / 359 ms |
| 计费恰一次（无同单同类型重复 payment_record） | PASS | PASS |
| 零超卖（无重复活跃仓占用） | PASS | PASS |

- 对账增量：资金类检查全 0；本轮 delta=-1（无新增违规，历史残留还收敛 1 项）。
- **口径声明**：20 线程 × 8 轮 × 7 步；JMX 单定时器 2300ms/步节流 → 请求速率 8.2/s、业务动作 ~2.2 单/s
  **为受控推进速率，不是吞吐上限**（无节流写吞吐为未测项，不作数字对外）。本档验证目标 =
  **业务闭环正确性（零超卖/恰一次/0 错）+ 端到端延迟画像**。
- 条件：JDK17、512m 定堆、限流关、MQ 消费关、sim dev-enabled、同机（保守口径）。
- 台账孤占复核：见 §4（非资金类，不阻断；根因已定位，修复待拍板）。

### 1.2 JVM 排障故事（96m vs 512m，同负载 100 线程 × 45s）

| 指标 | 96m | 512m |
|---|---|---|
| 吞吐 | 2,344.5/s | 2,987.1/s |
| avg 延迟 | 40ms | 31ms |
| GC 暂停次数 / 总时长 / 最大 | 2,040 / 3,996.7ms / 20ms（占 8.9%） | 98 / 277.0ms / 9.8ms（占 0.6%） |
| jstat 老年代 | 63.4MB 中占 52.1MB（~82%），YGC=85 CGC=30 FGC=0 | 余量充足 |

- 比例：暂停次数 20.8×、暂停总时长 14.4×、吞吐 -21.5%、延迟 +29%。
- 结论：高 churn 负载 + 小堆 → 并发 GC 兜底不 OOM 但延迟/吞吐明显劣化（"不死但很慢"，非泄漏——FGC=0、老年代无持续增长）；
  512m 稳定档、云上 768m 余量合理。完整故事档：`document/fixes/jvm-gc-pressure-96m.md`。

## 2. 交付物与文件

| 文件 | 用途 |
|---|---|
| `scripts/verify/batch17/_p03_write_capacity.ps1` | 写路径编排（sim/dev 双重置、基线对账、warmup、两档稳态、SQL 断言、对账增量门禁） |
| `scripts/verify/batch17/jmeter/swap-ops-write.jmx` | 写路径 JMX（keep-alive、每线程唯一用户、每轮 7 步、loops=8/10 定长、2300ms 单定时器） |
| `scripts/verify/batch17/_p03e_jvm_story.ps1` | JVM 对比驱动（96m/512m 两轮 + jstat；本机 shell 对 cmd/start 有等待现象，证据为同步骤手工执行并归档） |
| `scripts/verify/batch17/_p03_write_out.txt` | 最终 PASS 证据（19:03:44 - 19:11:41） |
| `scripts/verify/batch17/_p03e_out.txt` | JVM 对比证据 |
| `swap-sim/.../SimDevController.java` / `CabinetSim.java` / `CabinetSimTest.java` | 新增 `POST /sim/battery/soc`（模拟 BMS 满电上报，还电后回满可分配池） |
| `document/fixes/jvm-gc-pressure-96m.md` | JVM 故事档（现象→定位→处置→边界） |

原件（jtl/console/jmeter.log）：`diag-archive/p03-write-20260916-190344/`、`p03e-jvm-*`（不入 git）。

## 3. 关键设计与踩坑（精简版；全量 12 条见交接文档 §5）

1. keep-alive 必须显式（`use_keepalive=true` + `implementation=HttpClient4`），否则压测端端口耗尽 → 假故障（读路径旧 465/s 假象根因，批次16 已更正）。
2. 每线程唯一用户（`__longSum(13800000000, threadNum)`）：随机用户会命中"有进行中订单"互斥 → 400 级联。
3. 还电后电池不会自动满电（平台 BATTERY_IN 恒置 CHARGING，仅 SOC_REPORT 转 FULL）→ sim 新增 devSoc 端点，JMX 每轮还电后补一步满电上报。
4. 定时器语义：线程组级 ConstantTimer 作用于每个请求（2300ms/步，一轮 ~16s）；不要按"每轮一次"理解。
5. 定长循环（loops + scheduler=false）优于 duration 截止：后者停止瞬间留"在途单"，污染对账基线。
6. sim 与 DB 双重置缺一不可（`/sim/reset` 轮换 bootId 重建柜内存态 + `dev/device/reset` 重建分配池/归位电池）。
7. warmup（reset 紧接运行）错误计为容忍丢弃；正式档 0 错误容忍。
8. 对账门禁分级：资金类 = 硬门禁；非资金类差异 WARN + follow-up（原因见 §4）。

## 4. 台账孤占复核（交接文档 §6.2 follow-up，已推进到根因）

收尾复核实测：对账 total=18 = cell-battery-consistency 16 + transfer-ledger 2。与压测脚本内两时点
（baseline=8 → after=7）数字不同——**残留集合在每次 reset/压测后漂移**（机制见下），且两时点明细未留档
（平台 stdout 日志被重启覆盖），不再逐条归因；性质与影响不变（非资金类、不动钱/不动库存计数、不阻断结论）。

**reset 判定实验**（收尾期手动执行）：reset → 对账 **18→8**：
- 修复 13 项（含"孤儿引用"型：cell 引用电池但电池 cellId=null）；
- **新出现 3 项**（cells 1/4/5：前序步骤刚建立的正确绑定，被后续步骤的 detach 打掉）；
- 始终未修复 3 项（SWAP-T2-224621 的 cells 137/138/139）+ transfer 台账 2 项（09-13 遗留）。

**根因（两个独立机制）**：
- **A. T2 孤儿柜**：SWAP-T2-224621（09-13 历史测试柜）的种子电池（BAT-0121+）不存在 →
  `DevResetService` 满电分支 `battery == null → continue` **静默跳过、永不修复**（其 cells 引用停留在旧编号）。
- **B. reset 单遍处理不收敛**：逐仓"边扫边 detach 旧引用 + 绑新"在**交错引用图**上不能一遍收敛——
  后序仓的 detach 会打掉前序仓刚建立的一致绑定（cells 1/4/5 为直接实链：b1 在 cell1 刚绑好，
  处理 cell5 时因 cell5 旧引用=b1 被 detach）。压测并发写（TAKE/RETURN）产生交错引用图，为该机制提供输入。

修复方向（**待拍板，本次未改代码**）：① reset 重构为两阶段（先统一脱仓/清引用 → 再统一绑定，重跑至收敛）；
② 种子编号改稳定映射（不依赖柜排序下标）或补建缺失种子/退役 T2 孤儿柜；③ transfer 台账历史遗留单独处置。
详见 `document/pitfalls/dev-reset-nonconvergent-orphans.md`。

## 5. 验收对照（roadmap P0-3）

| 计划验收 | 实测 |
|---|---|
| 读/写各档 + 读 ≥5 分钟稳态 | ✅ 读三档 195s/档（批次16）；写两档 145s/档（本批） |
| 零超卖 / 计费恰一次 / 5xx=0 | ✅ 两档全过（SQL 断言，逐档重置后独立判定） |
| 资源画像 | ✅ 批次16（缓存/MySQL/Redis/GC）+ JVM 对比（本批） |
| 量级换算一页 | ✅ `document/knowledge/capacity-model.md`（批次16） |
| JVM 故事含前后数字 | ✅ 96m vs 512m（本批） |

本地全量：`mvn -B -ntp clean verify` **BUILD SUCCESS**（contract 4 + server 343 + sim 30 = **377/377**，覆盖率门禁全达标）。

## 6. 边界与未做

- 写路径节流下推进速率 ≠ 吞吐上限（无节流写压测为未测项）；
- JVM 故事未制造 OOM（96m 被 CGC 兜住；降至 ~64m 可复现但非必要）；
- 同机口径（JMeter 与平台互抢 CPU），数字用于相对对比与余量判断；
- 台账孤占修复未做（§4，待拍板）；"7 vs 18"两时点差异未逐条归因（如需可开 binlog 时间线）。
