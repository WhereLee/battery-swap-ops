# 批次13 —— S7 WP-B：代理/站点分润结算（2026-09-16）

## 交付

- `db/12-agent-settlement.sql`（幂等）：agent / station.agent_id / order_settlement（append-only，
  event_key 唯一）/ settlement_statement（同代理同周期唯一）；
- 域：`settlement`（entity/dao/AgentService/SettlementService/AdminAgentController/AdminSettlementController）；
- 口径：CASH=实收+券抵扣；次卡=floor(price/times)；月卡=0；欠费不分账（补缴补行）；
  退款=负向冲正（按原单代理）；分成 long 运算不丢分，share_bp 0/10000 边界合法；
- 结算单：顺序批 + 条件 UPDATE 抢挂 + 权威聚合；GENERATED→CONFIRMED→PAID；PAID 不可变；
  直营流水照落不入单；报表按代理聚合 + 直营汇总；
- 冲正退款端点 `POST /admin/refund/{orderNo}/reversal`（COMPLETED 专属，WP-0 禁裸退的正规通道）；
- 站点挂代理：StationAdminForm/AssetAdminService（代理启用校验；局部更新不误清归属）；
- 接线：BillingService（计费末分账）/ RefundService（冲正，写失败不阻断退款）/ ArrearsService（补缴补行）；
- 对账扩至十四组：⑫ 分账守恒 / ⑬ 结算单一致 / ⑭ 完成单必分账；
- Dev 闭环：Seeder 2 代理 + ST-002/ST-003 并把 C-009/C-010 划入（幂等）；
  DevReset 清结算单并释放挂单（**分账流水保留**——完成单历史分账是事实，防对账⑭误报）。

## 验证

- 单测 **375/375**（clean verify + 随机序；新增 SettlementServiceTest 9 / AgentServiceTest 2 + 既有接线断言）；
- 剧本 `scripts/verify/batch13/_c18_settlement.ps1` **12/12 PASS**；
- 值得记的行为：G1 欠费化后，"零余额完成 SWAP"的欠费=基础费 300+超时费 100=400（剧本原预期 100，
  以实际口径修正断言——这正是 G1 与 WP-B 联动的正确表现）。

## 实机踩坑（已修）

1. **编辑替换错位**：AdminRefundController 加冲正方法时把原 refund 方法尾部替换掉（编译暴露），
   读回补齐——教训：大段替换后立即编译。
2. **Mockito captor 复用**：同一 captor 两次 verify(times) 会累积捕获值导致索引错位
   （settlement 测试两处），改用独立 captor。
3. 剧本 notify 行误写 `-replace` 表达式，修正为 `$()?result=SUCCESS` 子表达式形态（避免变量吞问号坑）。

## 证据与提交

- `_c18_out.txt`（GATE-C18 PASS checks=12/12）；提交含代码+测试+剧本+文档；云端 rollout 随后。

## 遗留

- 打款仅状态留痕（无真实出账）；月卡基数 0 与周期自动生成后置（见知识档声明）。
