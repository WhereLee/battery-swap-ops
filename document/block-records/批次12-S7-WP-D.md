# 批次12 —— S7 WP-D：用户服务与营销（2026-09-15）

## 交付

- `db/14-user-service.sql`（幂等 + 快照列补丁）：work_order 加列（source/reporter/description）、
  arrears_record、coupon_template、user_coupon（含面额/门槛快照）、user_message、swap_order 加列
  （coupon_id/discount_fen）；
- 三域服务：`ArrearsService`（落单/门槛/补缴/减免/告警恢复）、`CouponService`（模板/发放/锁定/核销/释放/
  惰性过期）、`UserMessageService`（四类触达）；`UserReportService`（报障去重入口）；
- 端点：用户侧 `POST /user/report`、`GET /user/arrears|pay`、`GET /user/coupons`、
  `GET /user/messages|read`；管理侧 `admin/arrears`（read/waive）、`admin/coupon`（template create/list、
  grant）——全部 @PreAuthorize + 写端点 @AdminLog（守卫测试自动覆盖）；
- 接线：SwapOrderService（欠费门槛 + 券锁定 + **4 条终态释放路径**：取消/超时/发令失败/异常）、
  BillingService（券核销与实收口径、欠费落单）、WorkOrderService（报障建单 + 关闭回执）、
  RefundService（退款到账站内信）、ReconcileService（⑩欠费/⑪券，9→11 组）；
- Dev 闭环：Seeder 券种子（模板+前 3 用户）；DevReset 扩展（欠费/券/站内信清理 + 发行计数回退 +
  **报障去重键清理**）。

## 关键口径

- `fee_fen`=实收（基础费-券抵扣+超时费）；`COUPON_DEDUCT`=平台补贴流水；WP-B 分账基数=实收+抵扣；
- 欠费门槛 TAKE/SWAP 拒绝、RETURN 放行；
- 对账③收费白名单增 COUPON_DEDUCT。

## 验证

- 单测 356/356（clean verify + 随机序；新增 17 个）；既有构造变更 6 个测试类同步更新；
- 剧本 `scripts/verify/batch12/_c20_user_service.ps1` **15/15 PASS**（报障/欠费/券/站内信全链路）。

## 实机踩坑（已修）

1. **快照列忘重放迁移**：db/14 二次编辑加列后未本地重放 → 券插入 "Unknown column value_fen"（
   脚本幂等的好处：重跑即补列）——教训：改迁移后必须立即重放并复核 `SHOW COLUMNS`；
2. **故障注入须锁定柜**：门锁卡死注入在 C-002，而下单未指定柜 → 分配到别的柜导致注入无效；
   剧本改为携带 `cabinetNo=SWAP-C-002`；
3. **报障去重跨轮复跑**：10 分钟窗口使复跑返回上轮已关闭工单 → dev reset 增清去重键（联调语义）；
4. `fee_fen` 口径：原记"基础费基数"，剧本暴露后改为"实收（含券抵扣扣减）"，实收/补贴分列。

## 证据与提交

- `_c20_out.txt`（GATE-C20 PASS checks=15/15）；提交含代码+测试+剧本+文档；云端 rollout 随后执行。

## 遗留

- 券核销后退款不退（声明）；月卡/折扣型券后置；欠费补缴的分账补行随 WP-B 落地。
