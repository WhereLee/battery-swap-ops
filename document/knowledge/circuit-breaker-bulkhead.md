# 知识：熔断与舱壁的标准形态（S3.8 WP3）

> 代码：`DeviceDownlinkGuard`（Resilience4j 程序化 API）+ `CommandDispatchService` 接入。
> 配置：`resilience4j.circuitbreaker.instances.deviceDownlink` / `...bulkhead...`（application.yml）。
> 证据：`scripts/verify/batch4/_c3_breaker.ps1`（停 sim→6/6 失败→OPEN→NOT_PERMITTED→恢复 CLOSED）。

## 1. 为什么是"舱壁在外、熔断在内"

装饰顺序：`Bulkhead( CircuitBreaker( 真实调用 ) )`。

- 舱壁满（过载）**不计入**熔断失败率——过载是本地资源问题，设备可能完全健康；计入会把"平台自己忙"误判成"设备故障"；
- 熔断打开时动作不执行 → 不占舱壁许可，过载与故障两类快速失败互相独立。

## 2. 参数与状态机（deviceDownlink）

```
sliding-window: COUNT_BASED 10 次
minimum-number-of-calls: 5
failure-rate-threshold: 50%
wait-duration-in-open-state: 5s（到点自动转 HALF_OPEN）
permitted-calls-in-half-open: 3（探测成功→CLOSED；失败→再 OPEN）
bulkhead: 并发 8 / 等待 50ms（拿不到许可立即降级）
```

## 3. 超时：同步路径的取舍（如实声明）

Resilience4j `TimeLimiter` 只支持 `CompletionStage`；本调用链是同步 `RestTemplate`。
标准做法是**socket 级超时兜底**（connect 2s / read 3s，已是硬超时），而不是为了用 TimeLimiter
把同步调用包成异步线程池（多一层调度与线程消耗）。此处是"标准实践下的合理替代"，不是最小实现偷懒。

## 4. 降级语义随调用点不同（关键设计）

| 调用点 | 熔断/舱壁触发时 | 原因 |
|---|---|---|
| `dispatchPrepared`（开仓下发） | 返回哨兵 `code=-1` → 走"柜拒绝"分支：记 SEND_FAILED + 抛业务异常 | 面向用户：设备不可用时**快速失败**比让用户等/静默挂起体验好；订单走既有补偿（释放预占+关单） |
| `queryState`（对账查询） | 返回 `null`（与"查询失败"同语义） | 面向后台：对账任务下轮再试，不阻断业务、不再打设备 |

## 5. 可观测与告警

- `/actuator/circuitbreakers`（当前状态）、`/actuator/circuitbreakerevents`（状态变迁与 NOT_PERMITTED 计数）；
- 护栏触发 `log.warn("[resilience] ...")`，与 S3.6 告警体系同源可接（roadmap：CB OPEN 时 raise CABINET_FAULT/DOWNLINK_DEGRADED 告警）。
- 本地联调脚本以 actuator 端点断言状态机，不依赖日志文本。

## 6. 已知边界

- 对账任务（QUERY_STATE）同样经过该护栏：sim 整体宕机时对账流量也会计入失败率——这是有意的（它也是设备依赖调用）；若未来对账量级大，应独立 instance（不同阈值）。
- 单实例验证；多实例下每个实例各自持有熔断状态（Resilience4j 默认内存态）。跨实例统一熔断需共享状态（如 Redis CB）——**未做，如实声明**。
