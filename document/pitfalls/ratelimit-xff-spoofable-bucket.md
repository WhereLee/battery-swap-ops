# 坑位：登录限流的桶键取 X-Forwarded-For 左起第一段 → 一行 header 绕过撞库防护（批次33）

> 类型：安全缺陷（P1，认证面）｜发现方式：**独立代码审计**（AUD-3）
> 影响面：`admin-login` 与 `user-login` 两个 IP 维度限流器（全套撞库防护的唯一手段）
> 状态：**已修复 + 单测 + 实机剧本 `_c36`**；部署侧补了受信代理配置（否则反代后所有人共用一个桶）

## 现象

管理端/用户端登录的爆破防护只有一条：`@RateLimit(dimension = IP, permits = 5, windowSeconds = 5)`。
旧实现的桶键是：

```java
String forwarded = request.getHeader("X-Forwarded-For");
if (forwarded != null && !forwarded.isBlank()) {
    return forwarded.split(",")[0].trim();   // ← 左起第一段 = 客户端可任意伪造
}
return request.getRemoteAddr();
```

攻击者每个请求换一个 `X-Forwarded-For: 10.0.0.<i>`，就得到**一个全新的令牌桶**，
于是"5 次/5 秒"永远不触发——限流等于不存在，唯一剩下的成本是 BCrypt 的计算量。
部署形态是 systemd 直连（无反向代理）时，没有任何组件会覆盖这个 header。

## 根因

**把"身份"字段交给了被认证方自己声明**。XFF 的语义是"代理链上由每一跳追加"，
所以只有**最后一个可信跳**追加的那一段是可信的；左起第一段恰恰是客户端最先写进去的、完全不可信的值。

正确解析要同时满足两个条件：
1. **只有直连地址在受信代理列表里时**才看 XFF（直连场景下 XFF 全是自编的）；
2. 看的时候从**最右侧**往左找第一个非受信地址——nginx 的 `$proxy_add_x_forwarded_for`
   是"客户端伪造的整串 + 真实对端"，右端才是代理背书过的。

## 修复（批次33）

1. 新增 `common/ratelimit/ClientIpResolver`：默认**不信任任何 XFF**；受信代理才解析，且取最右非受信地址；
   支持精确 IP 与 CIDR（IPv4/IPv6 通用，按字节前缀比较）；脏配置/脏输入一律按"不可信"处理，不抛异常。
2. `RateLimitProperties` 新增 `trusted-proxies`（默认**空列表**——刻意的安全缺省：宁可粗（按对端聚合）
   也不要可绕过（按伪造值分桶））。
3. `RateLimitAspect` 改为委托解析器。

## 部署侧连带（这条不做就是"修了个假"）

加了 nginx 反代之后，**所有请求的对端地址都变成 127.0.0.1**：
- 若不配置受信代理 ⇒ 所有用户共用一个桶，5 次/5 秒会误伤正常用户（功能性回归）；
- 配置 `SWAP_RATELIMIT_TRUSTED_PROXIES=127.0.0.1` 后 ⇒ 解析器从 XFF 最右取真实客户端，
  既回到"按人分桶"，又不可被伪造绕过。

`scripts/cloud/rollout-web.sh` 的 step4b 会幂等追加该变量并重启服务，随后用 8 次带不同伪造 XFF 的
登录尝试自检（期望出现 429）。

## 回归网

| 层 | 文件 | 守什么 |
|---|---|---|
| 单测（解析规则） | `ClientIpResolverTest`（7 例） | 默认忽略伪造 XFF；**换 100 个 XFF 仍只有一个桶**；受信代理取最右；多级代理链；CIDR 边界；脏值不抛；对端缺失→`unknown` |
| 单测（切面行为） | `RateLimitAspectTest#ip维度忽略伪造XFF` | 通过真实请求上下文断言：20 个不同 XFF 落进同一个桶，且桶键里不含伪造值 |
| 实机剧本 | `scripts/verify/batch33/_c36_xff.ps1` | 10 次不同伪造 XFF 的登录爆破 → 前 5 次 400、后 5 次 **429**；窗口过后恢复（是窗口不是永久封禁） |

实机证据（`_c36_out.txt`）：

```
INFO  burst codes: 400,400,400,400,400,429,429,429,429,429 (in 548 ms)
PASS  P02 rotating X-Forwarded-For does NOT evade the limiter (throttled=5 of 10)
PASS  P04 limiter is a window, not a lockout (admits traffic again after the window)
=== C36 summary: PASS=5 FAIL=0 ===
```

## 经验

1. **任何"从请求里取身份/地址"的代码都要先问一句"这个值谁写的"**：XFF、`X-Real-IP`、
   `Forwarded`、甚至自定义的 `X-User-Id` 都同理；可信来源只能是传输层（TCP 对端）或受信代理追加的段。
2. **安全缺省应当是"拒绝"而不是"便利"**：`trusted-proxies` 默认空会带来"反代后共用一个桶"的
   功能代价，但这个代价是**可见的**（用户被误伤会立刻暴露）；而默认信任 XFF 的代价是**不可见的**
   （防护静默失效，没有任何报错）。
3. **修安全配置要连带修部署**：只在代码里改对，部署环境没配受信代理，等于把"可绕过"换成"误伤"——
   两边都必须在同一批里做完并写下自检。
