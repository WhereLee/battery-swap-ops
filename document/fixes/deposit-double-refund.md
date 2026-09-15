# fixes/deposit-double-refund —— 押金二次退款与人工退款状态白名单（S7 WP-0）

> 发现：S7 方案审查（子 agent 事实核验 + 设计批评）2026-09-14。修复：WP-0（先行独立提交）。

## 现象与根因

1. **押金二次退款（管理端可达）**：
   - 可退口径 `RefundService.REFUNDABLE_TYPES` 含 `DEPOSIT`，`refundableAmount` 按 payment_record 求和；
   - 但押金退还实际走退租 RETURN 的 `WalletService.refundDeposit`（押金转入余额并清零）；
   - 二者叠加：TAKE 单缴费 9900 → RETURN 已退押金 → 对 TAKE 单再调 `admin/refund`，
     可退金额仍按 DEPOSIT 流水返回 9900 → **二次退现**。
2. **人工退款无状态约束**：`admin/refund` 对 COMPLETED 单可退（资金已结算入账，含未来分账）。
3. **退款单无操作人列**：`refund_record` 无 operator（S7-A 通过 admin_op_log + 加列接通，本 WP 不动 schema）。

## 修复（本 WP）

- `REFUNDABLE_TYPES` 移除 `DEPOSIT`：**押金退还只走退租 RETURN 流程**（注释与文档声明）；
- `AdminRefundController`：
  - COMPLETED → 拒绝，文案指向冲正流程（S7-B 落地负向冲正流水）；
  - 可退金额为 0 → 拒绝"无可退金额"（0 元退款对人工通道无意义；补偿通道的 0 元核销单不受影响）；
- 单测：`RefundServiceTest`（口径修正：押金排除/扣减已退重算）、新增 `AdminRefundControllerTest`（三态断言）。

## 证据

- `mvn -B -ntp test`（含随机顺序）全绿；WP-0 提交见批次记录。
- 残留风险与后续：COMPLETED 单的人工退现需求由 S7-B 的冲正流水承接（退款成功同事务写负向分账行），
  在此之前管理端对已完成单的退款请求会被明确拒绝（不再有资金漏洞）。
