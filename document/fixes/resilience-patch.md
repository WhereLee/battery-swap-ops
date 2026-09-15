# fixes/resilience-patch —— 韧性补丁（S7，G1/G2/G3；G4/G5 声明）

> 来源：S7 防呆/极限场景审查（用户关注"最后一秒"类竞态）。三处修复 + 两处接受声明。

## G1 计费硬失败欠费化（核心）

- **原缺陷**：`BillingService.charge` 在余额不足/押金不足时抛 `RRException` → 事件事务整体回滚 →
  sim/MQ 持续重投失败 → 订单卡 OPENED → 取电超时关单 → **物财分离**（电池已交付，账务未落）。
  这违背本项目"事件是事实源"的设计原则。
- **修复**：扣费失败不再回滚——实收=实际扣缴；差额落欠费单（`BALANCE_FEE` / `DEPOSIT` / `OVERDUE_FEE`
  来源标注，db/15 加 `arrears_record.reason`）+ `ORDER_ARREARS` 告警；后续下单由欠费门槛拦截
  （TAKE/SWAP 拒绝，RETURN 放行）。
- **口径**：`order.fee_fen` = 实际扣缴（基础费实收 + 超时费实收）；欠费在 arrears 单（对账⑩守护自洽）。
- **验证**：`BillingServiceTest`（余额不足/押金不足两条欠费化用例）；`_c20` 检查 16-18：
  零余额完成 TAKE → 订单 COMPLETED + 欠费 10200（300 基础费 + 9900 押金）→ 下一单被拦。

## G2 报障"未关单"复核

- **原缺陷**：去重仅按 10 分钟 Redis 窗口；窗口失效后同一柜再报，若上单仍 OPEN 会重复开单。
- **修复**：窗口未命中时，先查"同用户同柜未关闭工单"（`findOpenUserReport`，状态 < VERIFIED），
  命中则复用并刷新去重键；Redis 异常仍 fail-open。
- **验证**：`UserReportServiceTest.未关单复核复用`。

## G3 渠道对账 IO 互斥

- **原缺陷**：人工导入/重建与日终任务并发时，双方各自"清 OPEN + 重建"，极端下互相删插
  （唯一键防重，但可能丢本轮差异——下一轮自愈）。
- **修复**：`AdminChannelReconController` 的导入/重建 + `ChannelReconTask` 统一走
  `JobLockService("channel-recon-io")`；锁被占时拒绝（人工）或跳过本轮（任务）。
- **验证**：`AdminChannelReconControllerTest`（锁内委托/锁占拒绝）。

## G4/G5 接受声明（不修，如实标注）

- **G4 超期转人工 vs 归还同秒**：订单 CAS 单一赢家；扫描赢则归还事件只落设备台账、订单保持 EXCEPTION
  （退款补偿按 EXCEPTION 全退，"服务事实被免除"）。属极低概率且资金偏向用户，接受；由告警可见。
- **G5 券发放并发**：两个管理员同时对同一用户发券，限领计数读后判极端下可多发一张。
  发券为低频运维动作且可审计，接受（P3）。

## 已验证的既有防呆（不在本补丁范围）

支付终态仲裁（资金优先/迟到成功补记）、取消 vs 开门 CAS、券 4 条终态释放、券并发锁定、
欠费补缴幂等+告警恢复、Redis 故障 fail-open（报障/代际守卫/任务锁）——见各知识档。
