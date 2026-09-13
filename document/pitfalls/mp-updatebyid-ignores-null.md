# 坑位：MyBatis-Plus `updateById` 默认忽略 null 字段（置空必须显式 UPDATE）

**现象**（S4.5 批二，电池"转在途"）：
`updateBattery` 里 `battery.setCellId(null)` 后 `batteryDao.updateById(battery)`，DB 中 `cell_id` 仍是旧值；
剧本断言"在途（cellId=null）"失败。

**根因**：
MyBatis-Plus 默认字段策略 `NOT_NULL`——`updateById` 生成的 SQL 只包含非 null 字段；
`null` 被静默跳过（这是"防误清字段"的设计，但置空场景必须换写法）。

**标准处置**：
置空/条件更新一律用 `LambdaUpdateWrapper` 显式 `.set(字段, null)`：
```java
batteryDao.update(null, new LambdaUpdateWrapper<BatteryEntity>()
        .eq(BatteryEntity::getId, id)
        .set(BatteryEntity::getCellId, null)   // 显式置空
        .set(BatteryEntity::getStatus, CHARGING));
```

**同类风险自查**：
- 本项目其余"清空"语义（`cell.battery_id=null`、`lock_order_id=null`、`holder_user_id=null`）
  历史上都用了显式 UPDATE——这次是新代码踩中，说明**新写编辑类方法时要过一遍"是否有字段需要置空"**。
- `insert` 不受影响（null 可写入）；`updateById` 与 `LambdaUpdateWrapper` 混用时注意字段策略差异。

**记录**：`_c10` 剧本实机抓到（单测 mock 无法体现 SQL 字段策略）。
