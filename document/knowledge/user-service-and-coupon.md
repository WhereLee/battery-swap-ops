# 用户服务与营销（S7 WP-D）

> 三条业务线闭环：**报障→工单**（用户侧服务入口）、**欠费闭环**（超时费不足额→补缴/减免→下单门槛）、
> **优惠券**（发→锁→核销/释放，含分账口径）；另附**站内信**触达（券/欠费/退款/工单四类）。

## 1. 模型（db/14）

| 表 | 说明 |
|---|---|
| `work_order` 加列 | `source`(ALARM/USER_REPORT) / `reporter_user_id` / `description` |
| `arrears_record` | 欠费单：`order_id` 唯一；amount/settled/status(1 OPEN/2 SETTLED) |
| `coupon_template` | 券模板（v1 FIXED 立减）：value/min/total/issued/per_user_limit/有效期 |
| `user_coupon` | 用户券：status(1 UNUSED/2 LOCKED/3 USED/4 EXPIRED) + **面额/门槛快照**（模板改价不影响已发券） |
| `user_message` | 站内信：type(REWARD/ARREARS/REFUND/WORK_ORDER)/read_flag |
| `swap_order` 加列 | `coupon_id` / `discount_fen`（实收= fee_fen，券抵扣单列） |

## 2. 报障 → 工单

- 用户端 `POST /user/report`（柜/仓/类型/描述）：**同用户同柜 10 分钟去重**（Redis；近重返回既有工单；
  Redis 故障 fail-open 不阻断报障）；限流 3/分钟；
- 严重级映射：DEVICE_FAULT=HIGH / CELL_FAULT·PAYMENT=MEDIUM / OTHER=LOW；自动 SLA 计时；
- 管理端沿用现有工单流程（operator 取管理端身份）；**关闭时向报障人发站内信回执**；
- DevReset 同时清理报障去重键（联调复跑必需）。

## 3. 欠费闭环

- 产生：`BillingService.chargeOverdue` 不足额 → 同计费事务落/累加欠费单（`order_id` 唯一幂等）+ 站内信
  + 保留 ORDER_ARREARS 告警；
- **下单门槛**：`SwapOrderService.create` 对 TAKE/SWAP 拒绝 OPEN 欠费（文案明确）；**RETURN 放行**（防逼停归还）；
- 补缴：`POST /user/arrears/{id}/pay`（余额 CAS 扣款与结清同事务；不足拒绝）；
- 减免：`POST /admin/arrears/{id}/waive`（客服/超管，审计留痕）；
- 结清/减免 → `markRecovered(ORDER_ARREARS)` 自动关告警 + 站内信；
- 不变量⑩（对账）：OPEN 欠费金额自洽（amount>settled≥0）。

## 4. 优惠券（状态机 + 分账口径）

- 领取：管理端 `POST /admin/coupon/template`（创建）+ `POST /admin/coupon/grant?templateId&userIds`
  —— 每人限领校验 + **发行量 CAS（防超发）** + 面额快照 + 站内信；种子（dev）自动给前 3 个用户发新客券；
- 锁定：下单携带 `couponId` → 校验（归属/UNUSED/未过期/门槛）→ CAS UNUSED→LOCKED（绑订单）；
  **仅余额计费单可用**（有可用套餐时下单拒绝）；RETURN 禁券；
- 核销：计费（余额路径）CAS LOCKED→USED，抵扣 = min(面额, 基础费)；
- **释放（订单终态统一钩子，4 条路径全接）**：用户取消 / 超时关闭 / 发令失败补偿 / 异常终止 → LOCKED→UNUSED；
  套餐路径下若券仍锁定（下单后套餐生效）→ 计费后释放；
- 到期：惰性置 EXPIRED（领取时快照 expire_time）；
- **账务口径**：实收 `fee_fen`；`COUPON_DEDUCT` 为平台补贴流水（金额=抵扣）；
  WP-B 分账基数 = 实收 + 券抵扣（代理不因用户用券少分）；
- 与对账③联动：收费类型白名单增 `COUPON_DEDUCT`（防全额券单零流水被误报"完成无支付"）；
- 不变量⑪（对账）：LOCKED 必挂进行中订单；USED 必挂完成订单。

## 5. 站内信

- 写点：券发放（REWARD）/ 欠费产生与结清（ARREARS）/ 退款到账（REFUND）/ 报障工单关闭（WORK_ORDER）；
- 写失败只告警不影响业务（与审计同纪律）；读端点 `GET /user/messages`（unreadOnly/limit）+
  `POST /user/messages/{id}/read`（幂等，越权拒绝）。
- 短信/微信推送明确不做（声明）；站内信为触达形态。

## 6. 证据

- 单测：`ArrearsServiceTest`(6) / `CouponServiceTest`(5) / `UserMessageServiceTest`(3) /
  `UserReportServiceTest`(3) / `BillingServiceTest.用券抵扣` / `SwapOrderServiceTest` 欠费门槛与券锁定；
- 剧本 `scripts/verify/batch12/_c20_user_service.ps1`：**15/15 PASS**——报障去重+工单闭环+回执 /
  欠费制造（超时费不足）→拒单→补缴→放行 / 券发放→锁定→**发令失败释放**→再用→核销→`COUPON_DEDUCT` 流水；
- 对账不变量总数 9→11（⑩欠费/⑪券）。

## 7. 边界与声明

- 券仅 FIXED 型（折扣型后置）；核销后退款不退券（v1 声明）；到期惰性处理（无定时任务）；
- 欠费按订单唯一（同单多次不足额累加，来源 reason 标注）；欠费补缴的分账补行随 WP-B 落地（ARREARS_SETTLE）；
- 站内信限 100 条/次查询，无推送通道；
- **韧性补丁（S7）**：计费硬失败已欠费化（不再回滚已交付事件）；报障去重窗失效后有"未关单复核"；
  券发放并发极端下可多发一张（P3 接受，见 fixes/resilience-patch.md G5）；
  超期转人工与归还同秒的 CAS 单赢家语义（G4 接受声明）。
