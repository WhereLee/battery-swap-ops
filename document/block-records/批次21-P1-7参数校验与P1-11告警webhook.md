# 批次21：P1-7 参数校验（jakarta.validation）+ P1-11 告警出站 webhook

> 日期：2026-09-16 | 状态：完成 | 单测 356/356 | 证据：`scripts/verify/batch21/_c25_out.txt`（13/13 PASS）、`_c26_out.txt`（16/16 PASS）

## 摘要

| 项 | 结果（实测） |
|---|---|
| 参数校验（P1-7） | 下单 `type`（@NotBlank+@Pattern）、充值 `amountFen`（@NotNull+@Min(1)）、退款 `amountFen`（@RequestParam @Min(1)）→ 统一 400 + "参数校验失败: {field} {msg}"；非法 JSON → 400 "请求体不是合法 JSON" |
| 合法路径不受影响 | 充值 100 → 200 code=0；合法表单进入业务层（缺幂等键=业务 400"幂等"，非校验 400）——实测区分 |
| webhook（P1-11） | RAISED / HANDLED / RECOVERED 三类事件出站；**22s / 4s 到达**（停 sim 离线→恢复心跳）；**HMAC-SHA256 字节级验签通过**（receiver 侧 sign_ok=true） |
| 隔离性 | 接收端停机：告警照常入库（26s）、health 200、登录业务路径正常、心跳恢复自动关；**19 处 webhook 失败日志**（重试证据） |
| 可靠模型 | 单线程+有界队列（满丢弃计数）/ 内联重试 3 次（500ms×n 退避）/ 连续失败 5 次熔断 60s / 一切异常吞掉计数——**绝不阻塞告警主链路** |
| Redis 故障回归 | 沿批 19 的快速失败语义，无新增级联（validate 与 webhook 均为同步轻路径） |

## 实现清单

- **pom**：`spring-boot-starter-validation`。
- **application.yml**：`swap.alarm.webhook.url/secret`（env `SWAP_ALARM_WEBHOOK_URL`/`SWAP_ALARM_WEBHOOK_SECRET` 注入，仓库零明文；空 url=整体禁用）。
- **Form/Controller**：`CreateOrderForm`/`RechargeForm` 注解；`UserOrderController`/`UserWalletController` `@Valid`；`AdminRefundController` 类级 `@Validated` + 参数 `@Min(1)`。
- **RRExceptionHandler**：新 3 个 handler——`MethodArgumentNotValidException`（首条字段错误）/`ConstraintViolationException`/`HttpMessageNotReadableException` → 统一 400。
- **AlarmWebhookProperties**（`alarm/config`）：url/secret/超时/重试/队列/熔断参数。
- **AlarmWebhookNotifier**（`alarm/service`）：单发送线程 `ThreadPoolExecutor(1,1)` + `ArrayBlockingQueue(1000)`；JDK HttpClient（connect 1s / 请求 3s）；`X-Swap-Sign = hex(HMAC-SHA256(secret, body))`、`X-Swap-Event` 头；sent/failed/dropped 计数；`@PreDestroy` 排空。
- **AlarmService 挂点**：raise/enqueue 后 `notify("RAISED")`、handle 后 `notify("HANDLED")`；`markRecovered` 新增：update 前取未处理列表 → rows>0 时逐条构造 `RECOVERED` 信封（CAS 语义不变，并发人工处理抢先则 rows=0 不通知）。
- **单测 +7**：AlarmWebhookNotifierTest ×4（签名/重试/禁用/熔断，JDK HttpServer 假接收端）；FormValidationTest ×2（编程式 Validator）；AlarmServiceTest +1（RECOVERED 通知）。
- **附**：SwapMetrics Gauge 全部显式 `strongReference(true)`（见 pitfalls——全量测试暴露的 flaky 根因）。

## 剧本证据

### `_c25_validation.ps1`（13/13）

- 校验拒绝 9 项：空 type / 非法 type（FOO）/ 坏 JSON / 充值 0 / 充值负数 / 充值缺字段 / 退款参数 0——均 400 且携带统一中文消息（断言中文即校验的就是消息内容本身）。
- 放行 4 项：合法充值 200 + `"code":0`；缺幂等键下单 400“幂等”（业务层证据）；缺金额退款 400“订单不存在”（业务层证据）。
- 附带修复：PS 5.1 `-OutFile` 模式 `$r.StatusCode` 取不到（0）→ 成功分支按 HTTP 语义取 200/回退。

### `_c26_webhook.ps1`（16/16 PASS）

- 链路：停 sim →（心跳 30s 超时 + 2s 扫描）→ BATCH_OFFLINE 告警 → outbox + webhook 双出站；启 sim → 心跳恢复 → RECOVERED。
- 实测时序（终版）：停 sim 后 **24s** 收到 RAISED（sign_ok=true、body 含 traceId）；sim 启动后 **4s** 收到 RECOVERED（sign_ok=true）——签名在 Python receiver 端用**原始字节**独立验算，杜绝编解码误差。
- 隔离轮（严格三段式）：① 先等“离线家族列表清空”（防残留充当假阳性）→ ② 停 receiver + 停 sim → **26s** 新告警照常入库（`new alarm stored`）→ health 200 / 登录正常 → ③ 启 sim → **心跳回归检查**（redis `swap:online:*` 键重现行，等旧键 TTL 30s 过期后再判定，排除假阳性）→ 告警自动恢复关闭；服务日志含 70 处 webhook 失败/重试记录。
- 清场设计：剧本先 handle 残留离线告警 + 清 `swap:alarm:dedup:*` 键，保证“全新一轮 raise”确定性（不受 300s 去重窗口干扰）。
- 开发过程（诚实记录）：首版/二版因 PowerShell 函数返回值展开语义导致 `(fn).Count` 在“恰好 1 条命中”时静默失效（假 FAIL）；试图用 `return , $items` 修复反向引入了 `@()` 双包装（Count 恒 1）导致 found 恒真/clear 恒假的第二层假象——最终以“普通 return + 调用处 `@(fn).Count`”收敛，细节与三态实验见 `document/pitfalls/ps-function-return-unroll-count.md`。

## 诚实边界

- webhook 为**尽力而为**模型（内联重试 + 熔断 + 丢弃计数），非 MQ 的持久补投语义——持久化诉求走 `swap-alarm` MQ/outbox（批 19 已验证）；两者互补：MQ 面向 Agent、webhook 面向外部值班系统。
- 重试在单发送线程内联 sleep（会推迟队列中后续事件）；容量 1000 + 熔断兜底，事件高频场景需改异步退避调度——当前告警治理本身有去重/限速（单类型 30/min），量级匹配。
- 面板/接收端均为验证态（Python mock），未对接真实钉钉/企业微信网关（无账号密钥；对接只是 URL/格式差异）。
- 参数校验覆盖了 3 个高危入口（下单/充值/退款）；其余接口沿用服务层手工校验（不追全量注解化）。
- 迭代备注：`_c26` 的终版断言 16 项（较初版 12 项新增：隔离前清空前置、心跳回归检查、隔离轮“新告警”语义强化）；开发期基于 `.Count` bug 的观测一度形成“sim 重启慢（2.7 分钟才心跳）”的错误假设，该假设**被证伪并已丢弃**（sim 重启实测 4s 级；观测本身来自失效断言）——不写入结论。
