# 知识：运营看板指标口径（S4.5 批一）

> 代码：`DashboardService` / `AdminDashboardController`；接口 `GET /admin/dashboard/overview`。
> 证据：`scripts/verify/batch5/_c8_dashboard.ps1`（口径合法性 + 缓存命中 + 回源计数=1）。

## 1. 指标定义（口径即契约）

| 指标 | 公式 | 数据源 | 说明 |
|---|---|---|---|
| 满电保有率 `fullBatteryRate` | FULL 电池 /（全部非退役电池） | DB `battery`（事实源） | 反映可换资源池健康度 |
| 站点可用率 `stationAvailabilityRate` | 有满电可换的运营中站点 / 运营中站点总数 | 站点 DB + 分配池 Redis（与分配同源） | 与用户端"能不能换到"一致；列出不可用站点号供运维 |
| 电池周转率 `turnoverRate` | 今日完成订单 / 非退役电池 | DB `swap_order` + `battery` | 简化口径（单日）；跨天/趋势在 S5 补 |

- 除零保护：无电池/无站点时返回 0，不抛错；
- 数值统一 4 位小数（前端展示再格式化）。

## 2. 为什么用"分配池"算站点可用性

- 用户能不能换电，取决于**分配池**（S2 起与 DB 条件更新同步维护），而非"电池表里 FULL 的数量"；
- 池是"可分配"语义（排除锁定仓/停用柜），与用户实际体验一致——**看板与分配同源**，避免"看板说能换、实际换不到"的裂缝。

## 3. 缓存边界（复用了两级缓存）

- 聚合结果键 `dashboard:overview`，L2 TTL **60s（按 key 覆盖，S4.5 新增能力）**，带抖动；
- 陈旧窗口 ≤60s：运营看板可接受；不缓存任何单点实时数据（站点可用数已在池里实时）；
- 缓存不可用自动直算（fail-open 只降性能）；
- 阅读路径：`admin/cache/stats → rebuild:dashboard:overview` 可确认回源频度（剧本断言 2 次读=1 次回源）。

## 4. 边界与后续

- 无时间维度（趋势/环比）、无按站/柜下钻、无导出——归 S5（可观测/报表）或前端阶段；
- `completedToday` 用服务器时区（Asia/Shanghai）当日 00:00 切分；跨时区部署需统一时区（S5 云部署注意）；
- 未接 Prometheus 指标（S5），当前以接口 + 日志呈现。
