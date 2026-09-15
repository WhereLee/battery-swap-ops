# 渠道对账 T+1（S7 WP-C）

> 资金域生产必备：渠道侧账单（真实场景为支付机构 T+1 对账文件）与平台侧支付单双向核对，
> 差异分类落单、人工处置、日报与告警闭环。mock 渠道下账单由 dev 端点生成（支持差异注入）。

## 1. 模型（db/13）

- `channel_bill`：渠道账单明细——bill_date（yyyy-MM-dd）/ channel / trade_no / amount_fen /
  status(SUCCESS/CLOSED) / imported_at；唯一键 `(bill_date, channel, trade_no)`；
- `recon_diff`：差异单——`(bill_date, channel, trade_no, diff_type)` 唯一；status OPEN/HANDLED/IGNORED；
  handled_by/handled_time/remark 为处置留痕。

## 2. 流程

1. **账单导出（mock，dev）**：`GET /api/pay/mock/bill/export?date=&anomaly=`——按日从 pay_order
   （非 WAIT、callback_time 落在账单日）生成 CSV（`bill_date,trade_no,amount_fen,status`）；
   `anomaly` 逗号分隔注入：`missing`（删成功行）/ `extra`（加渠道独有行）/ `amount`（改首行金额）/
   `status`（翻首行状态）——供剧本与演示构造差异；
2. **导入**：`POST /admin/recon/import?date=&channel=`（权限 `admin:recon:import`）——事务内
   "先清当日同渠道再插入"（幂等覆盖）→ 立即对账；行级校验（4 列/日期一致/金额数字/状态枚举），
   非法行整体拒绝；
3. **对账（幂等重建）**：渠道账单 ↔ 平台支付单；**当日同渠道 OPEN 差异按重算结果重建；
   HANDLED/IGNORED 键保留留痕**（防"账单已平但仍 OPEN 告警"）；差异 >0 → 告警
   `CHANNEL_RECON_DIFF`（归零 `markRecovered` 自动关）；
4. **每日任务**：`ChannelReconTask` 02:40（`swap.recon.channel-cron`）对账"昨日"；账单未导入 →
   记日志跳过；看护 beat 始终打点（注册于 TaskWatchdog，阈值 26h）；
5. **查询/处置**：`GET /admin/recon/report?date=`（按类型/状态聚合 + 账单数/平台单数）、
   `GET /admin/recon/diff?date=&status=`、`POST /admin/recon/diff/{id}/handle?status=HANDLED|IGNORED&remark=`
   （CAS OPEN→处置态，操作人取管理端上下文）；`POST /admin/recon/run?date=` 手动补跑。

## 3. 差异分类口径

| 类型 | 含义 |
|---|---|
| `CHANNEL_ONLY` | 渠道有、平台无（渠道多记/平台漏记） |
| `PLATFORM_ONLY` | 平台有、渠道无（渠道漏记/入账未清） |
| `AMOUNT_MISMATCH` | 两边流水号一致但金额不符 |
| `STATUS_MISMATCH` | 金额一致但状态不符（如平台 SUCCESS、渠道 CLOSED） |

平台侧参与对账口径：`status != WAIT` 且 `callback_time` 落在账单日；WAIT（未支付）不参与。

## 4. 证据

- 单测 `ChannelReconServiceTest`：导出与四类注入 / 解析校验 / 四类差异 / 重建保留 HANDLED /
  处置 CAS / 报表聚合；
- 实机剧本 `scripts/verify/batch11/_c19_channel_recon.ps1`：10/10 PASS（真实充值两笔 →
  四类注入导入 → 分类正确 → 处置 → 重导清 OPEN 留 HANDLED）。

## 5. 边界与声明

- 账单为本地 mock 生成；对接真实渠道时替换"导出/导入"两端实现（对账/差异/处置链路不变）；
- 暂不含退款通道侧对账（MOCK 渠道边界声明）；多通道已预留 `channel` 列；
- 渠道账单"送达延迟/月底补送"场景：导入以最后一次为准（幂等覆盖），已处置差异保留留痕；
- **韧性补丁 G3**：导入/重建与日终任务经 `JobLockService("channel-recon-io")` 互斥（人工被占拒绝、任务被占跳过）。
