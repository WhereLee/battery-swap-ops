# 知识：令牌桶限流的实现要点（S3.8 WP2）

> 代码：`RateLimit` / `RateLimitAspect` / `RateLimitDimension` / `RateLimitExceededException`。
> 接入：登录（IP 5/5s）、下单（USER 5/60s）、充值（USER 3/60s）、支付回调（GLOBAL 100/10s）。
> 证据：`scripts/verify/batch4/_c2_ratelimit.ps1`（20 并发 → 恰好 5 通过 + 15×429 + Retry-After）。

## 1. 为什么是令牌桶（而不是计数器/漏桶）

| 方案 | 特点 | 本项目取舍 |
|---|---|---|
| 固定窗口计数器 | 简单；窗口边界双倍突发（前 1ms+后 1ms 可过 2×limit） | 不做 |
| 滑动窗口 | 精确；需 ZSET 存每次请求，内存随 QPS 增长 | 不做 |
| **令牌桶** | 容量=突发上限、按速率补充；内存 O(1)（tokens+ts 两字段） | **采用** |
| 漏桶 | 恒定流出，不允许突发 | 不适合"允许合理突发"的接口防护 |

## 2. 实现要点

- **Lua 原子**：取时间→补令牌→扣减→写回→设 TTL 全在一次 `EVAL` 内，多实例无竞态超发；
- **时间用 Redis TIME**：不用客户端时钟，避免多实例时间偏差影响判定；
- **桶键 = `swap:ratelimit:{name}:{维度值[:SpEL 附加键]}`**：
  GLOBAL=global / USER=userId / IP=XFF 首个地址 / API=方法签名；SpEL 支持 `#p0`（位置别名）与参数名（Boot 默认 `-parameters`）；
- **Retry-After** = ceil(windowSeconds / permits)（补 1 个令牌的时间，近似值，只用于提示退避）；
- **TTL** = ceil(capacity / rate × 2)：空闲桶自动回收，Redis 不积累死键；
- **fail-open**：Redis 异常放行并告警——限流是保护层，不是正确性依赖（与缓存降级同哲学）。

## 3. 与业务的一致性

- 429 只影响"新请求"，不影响已在途请求；重试策略由客户端按 Retry-After 退避。
- 支付回调的 GLOBAL 桶是"网关重试风暴兜底"，容量刻意放大（100/10s），避免误伤正常回调；
  回调幂等（S3.4）才是正确性闸门——限流不承担资金正确性。

## 4. 已知边界

- 单机联调验证；多实例共享桶由 Redis 天然保证（Lua 原子），未做真双实例压测实证。
- Redis 故障窗口内无限流（fail-open 的代价），窗口内需要在接入层（网关/WAF）有第二道粗限流。
- 未做"按接口动态配置中心调参"（注解为静态声明）；若需要热调，把注解属性改为配置键索引即可（roadmap 未做）。
