# 代理与站点分润结算（S7 WP-B）

> 行业签名功能：站点归属（直营/代理商）→ 完成单按规则生成**append-only 分账流水** →
> 顺序批生成**结算单**（GENERATED→CONFIRMED→PAID，PAID 不可变）→ 报表与对账守恒。
> 退款走**负向冲正**、欠费补缴走**补行**——资金账与分账账始终一致。

## 1. 模型（db/12）

| 表 | 说明 |
|---|---|
| `agent` | 代理：编号唯一 / 分成比例 `share_bp`（万分比 0~10000）/ 结算口径（DAILY/WEEKLY/MONTHLY，v1 记录） |
| `station.agent_id` | 站点归属（NULL=直营）；管理端建站/更新可挂代理（校验代理启用） |
| `order_settlement` | 分账流水 append-only：`event_key` 唯一=幂等闸；`event_type`=ORDER / REFUND_REVERSAL / ARREARS_SETTLE；`base_type`=CASH / PLAN_TIMES / PLAN_MONTHLY / REVERSAL / ARREARS；`statement_id`（NULL=未结） |
| `settlement_statement` | 结算单：`UNIQUE(agent_id, period_start, period_end)`；金额四件套（base/agent/platform/subsidy）+ 操作人三件套 |

## 2. 基数口径（关键业务声明）

| 场景 | 口径 |
|---|---|
| CASH（余额计费/超时费实收） | 基数 = **实收 + 券抵扣**（券为平台补贴，代理不因用户用券少分；subsidy_fen 单列） |
| 次卡扣次 | 基数 = `floor(plan.price_fen / plan.total_times)`（整除余数归平台） |
| 月卡 | 基数 = 0，仅计 order_count（"套餐池分成待协议化"，v1 声明） |
| 欠费部分 | **不参与分账**（收入未实现；补缴时以 ARREARS_SETTLE 补行确认） |
| 退款 | REFUND_REVERSAL 负向行 = -退款额（按**原单代理**冲正，防站点归属变更串账） |

- 分配：`agent_share = base × share_bp / 10000`（long 运算 floor），`platform = base - agent`
  （**不丢分**）；share_bp 0=全平台 / 10000=全代理 均合法。
- 挂钩点：`BillingService.charge` 末尾（TAKE/SWAP/RETURN 三完成路径唯一汇聚点）；
  退款冲正在 `RefundService.apply` 成功后（同事务，写失败不阻断退款并告警日志）；
  补缴补行在 `ArrearsService.pay` 结清后。

## 3. 结算单与流程

- **顺序批语义**：生成=取该代理全部 `statement_id IS NULL` 流水（id 升序，上限 5000）；
  抢挂用条件 UPDATE（`statement_id IS NULL`）防并发双领；以实际挂单流水聚合为权威金额。
- **PAID 不可变**：迟到订单/退款冲正/补缴补行自然进入**下一期**；同代理同周期唯一（重复生成拒绝）。
- **直营**：流水照落（agent_id NULL，agent_share=0）但**不参与结算单**；报表中"直营"单列。
- 端点（`admin/agent` / `admin/settlement`）：代理 CRUD/启停（agent-mgmt:*）；结算单列表/详情/生成/确认/打款
  + 流水查询 + 报表（settlement:read/manage、report:read）；全部 @PreAuthorize + 写端点 @AdminLog。
- 冲正退款端点：`POST /admin/refund/{orderNo}/reversal`（仅 COMPLETED 单；reason=ADMIN_REVERSAL；
  退款成功自动写负向分账行——WP-0 "已完成单禁裸退" 的正规通道）。

## 4. 对账守恒（⑫⑬⑭）

- ⑫ **分账守恒**：每行 `agent_share + platform_share = base`（不丢分）；ORDER 行基数非负；
- ⑬ **结算单一致**：挂单流水合计=结算单四金额；无孤儿挂单（指向不存在结算单）；
- ⑭ **完成单必分账**：窗口内已完成 TAKE/SWAP（已计费）必有 ORDER 流水（防漏分账）。

## 5. 证据

- 单测：`SettlementServiceTest`(9，含基数/分成边界/幂等/冲正/补行/结算单 CAS)、`AgentServiceTest`(2)、
  `AdminRefundControllerTest.冲正*`、`AssetAdminServiceTest.建站挂代理`、接线断言（Billing/Refund/Arrears）；
- 剧本 `scripts/verify/batch13/_c18_settlement.ps1`：**12/12 PASS**——次卡折算(300→180/120) /
  CASH 分账 / 冲正退款(-100→-60/-40) / 欠费补缴补行(+400) / 结算单生成-确认-打款-PAID 不可变 /
  报表按代理聚合。

## 6. 边界与声明

- 打款为状态与留痕（MOCK 渠道，无真实出账通道）；月卡基数 0（协议化留后）；
- 结算口径周期字段仅记录（v1 不做自动周期生成，管理端手动生成顺序批）；
- 站点解绑代理走运维（管理端更新仅支持显式挂代理，不因局部更新误清归属）。
