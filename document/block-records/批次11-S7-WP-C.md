# 批次11 —— S7 WP-C：渠道对账 T+1（2026-09-15）

## 交付

- `db/13-channel-recon.sql`（幂等）：channel_bill / recon_diff；
- 域：`payrecon`（entity/dao/enums/service/controller/task 9 类）+ `AlarmType.CHANNEL_RECON_DIFF` +
  看护注册 `channel-recon`（26h）；
- 端点：`admin/recon`（report/diff/import/run/handle，权限 `admin:recon:read|import|handle`，
  写端点 @AdminLog）；dev 账单导出挂 `pay/mock/bill/export`（差异注入 missing/extra/amount/status）；
- 每日任务：02:40 对账昨日（`swap.recon.channel-cron`），账单未导入→记日志跳过，beat 打点。

## 关键设计

- **导入=幂等覆盖**（先清当日同渠道再插），**对账=幂等重建**：OPEN 差异按重算重建，
  HANDLED/IGNORED 保留留痕（防"账单已平仍告警"）；
- 四类差异：CHANNEL_ONLY / PLATFORM_ONLY / AMOUNT_MISMATCH / STATUS_MISMATCH；
- 平台侧口径：非 WAIT 且 callback_time 在账单日；WAIT 不参与。

## 验证

- 单测：`ChannelReconServiceTest` 8 个；总数 **335/335**（clean verify + 随机序全绿）；
  端点注解由 `AdminEndpointGuardTest` 自动守卫（新控制器 5 端点全通过）；
- 剧本：`scripts/verify/batch11/_c19_channel_recon.ps1` **10/10 PASS**——真实充值两笔（SUCCESS+CLOSED）→
  账单导出四类注入 → 导入 → 分类断言 → 处置 → 重导清 OPEN 保 HANDLED。

## 实机踩坑（剧本工程，已修）

1. **PS 变量名吞问号**（本轮最大坑）：`"$trade1?result=SUCCESS"` 被 PS 解析为变量 `${trade1?result}`（空），
   URL 变成 `/notify/=SUCCESS` → 服务端报"支付单不存在: =SUCCESS"，notify 静默失效、后续四类差异全假失败。
   纪律：**URL 中变量后紧跟 `?`/`&` 必须写 `${var}`**（已写入剧本头注释）。
2. notify 由 bodyless POST 改为 `curl.exe + ("{}" JSON body)`（PS 5.1 bodyless POST 行为不稳）。
3. 充值限流 3/分钟：剧本改用随机用户（13800000002-08）；
4. 差异注入作用于"当天最早一笔"——若该笔差异键已 HANDLED，重建会保留旧处置导致无 OPEN 可处置；
   剧本把"处置"步骤挪到 extra 注入后（RFAKE 行必为全新 OPEN 键）；
5. 告警去重窗口：剧本对告警断言放宽为"存在 CHANNEL_RECON_DIFF 告警行（含已恢复）"，避免连跑去重干扰。

## 证据与提交

- `_c19_out.txt`（GATE-C19 PASS checks=10/10）；提交含代码+测试+剧本+文档（见 commit）。
- 云端：db/13 + 换包 rollout（随后执行）。

## 遗留

- 退款通道侧对账（MOCK 边界声明）；真实渠道接入时替换导出/导入实现。
