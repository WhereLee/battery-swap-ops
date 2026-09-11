# 修复：dev 重置初版两个真 bug（实机剧本抓到）

**背景**：S4-pre dev 重置（`DevResetService`）单测全绿，但实机 `_c0` 剧本连续失败。
两个 bug 均属"mock 测不出来、只有真 SQL/真 Redis 会暴露"的类型。

## Bug 1：重置未清 `cell.lock_order_id` → 分配池为空

- 现象：reset 后新订单报"暂无可换的满电电池"。
- 根因：`AllocationService.rebuildFromDb → syncCellInto` 明确跳过 `lock_order_id != null` 的仓；
  reset 取消了活跃订单、归位了电池，但没有清仓上的订单锁 → 60 个仓全部被跳过。
- 修复：归位/清空仓时一并 `set(lockOrderId, null)`。
- 教训：**重建类逻辑的入口条件（哪些行会被跳过）必须对照调用方源码**；只看"数据看起来对了"不够。

## Bug 2：`notIn(condition, ...)` 条件传反 → 60 颗种子电池被当"额外电池"全部脱仓

- 现象：reset 结果 `extrasParked=60`，池依旧为空（电池 `cell_id` 被清）。
- 根因：MyBatis-Plus 条件式 `notIn(boolean condition, column, coll)` 的语义是
  "condition 为 true 才拼接 NOT IN"；原代码传 `seededBatteryIds.isEmpty()`（有种子时为 false），
  导致过滤被跳过、把所有电池（含刚归位的）当额外电池脱仓。
- 修复：改为 `!seededBatteryIds.isEmpty()`；空集合时不拼 SQL（全量即全部额外）。
- 教训：**条件式 wrapper 的布尔值是"是否拼接"，不是"集合是否为空"**——这是 MP 的常见误用点。

## 测试策略调整

- mock 无法执行 SQL 过滤与 Redis 池语义，单测只能覆盖流程编排；
- 为此保留**实机门禁 `_c0_dev_reset.ps1`**（重置幂等 + TAKE→取电→完成 E2E），
  作为这两个 bug 的回归防线；块记录中如实声明"单测覆盖不到 SQL 过滤"。
