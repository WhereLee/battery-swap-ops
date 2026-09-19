# 批次33 · 登录限流 XFF 信任边界（P1 修复）

> 日期：2026-09-19 ｜ 性质：**独立审计驱动的安全修复批**（AUD-3）
> 结论：修复 + 7 例单测 + 1 例切面实测用例 + 实机剧本 `_c36`（5/5 PASS）+ 部署侧受信代理配置

## 一、缺陷

`admin-login` / `user-login` 两个 IP 维度限流器（爆破防护的唯一手段）的桶键取
`X-Forwarded-For` 的**左起第一段**，而该值由请求方完全控制 ⇒ 每请求换一个伪造值即每请求一个新桶，
5 次/5 秒永不触发。详见坑位档 `document/pitfalls/ratelimit-xff-spoofable-bucket.md`。

## 二、修复

| 变更 | 内容 |
|---|---|
| 新增 `common/ratelimit/ClientIpResolver` | 默认不信任 XFF；受信代理才解析且取**最右非受信地址**；精确 IP + CIDR（v4/v6）；脏值按不可信处理 |
| `RateLimitProperties` | 新增 `trusted-proxies`（默认空＝安全缺省） |
| `RateLimitAspect` | 委托解析器；IP 维度注释同步（不再是"XFF 优先"） |
| `application.yml` | `swap.ratelimit.trusted-proxies: []` + 说明 |
| `scripts/cloud/rollout-web.sh` step4b | 幂等追加 `SWAP_RATELIMIT_TRUSTED_PROXIES=127.0.0.1` 并重启 + 8 次伪造 XFF 自检 |

**为什么部署侧改动是本批的一部分**：加了 nginx 反代后所有请求的对端都是 127.0.0.1，
不配置受信代理会把"可绕过"换成"所有用户共用一个桶"——安全缺陷变成功能回归。
两者必须同批完成。

## 三、验证

| 项 | 结果 |
|---|---|
| `ClientIpResolverTest` | 7/7 PASS（含"换 100 个 XFF 仍只有一个桶"、CIDR 边界、脏值不抛） |
| `RateLimitAspectTest` | 7/7 PASS（新增 `ip维度忽略伪造XFF`：真实请求上下文下 20 个不同 XFF → 1 个桶） |
| 实机 `_c36_xff.ps1` | **5/5 PASS**：`400,400,400,400,400,429,429,429,429,429`（548ms 内 10 个不同伪造 XFF）；窗口过后恢复 |
| 证据 | `scripts/verify/batch33/_c36_out.txt` |
| 修复前形态留档 | `git show <批次33 之前>:...RateLimitAspect.java` 保留 `forwarded.split(",")[0].trim()` 原实现（本记录与坑位档均引用了它） |

## 四、边界与如实申报

1. **我没有在修复前的代码上跑 `_c36` 做对照实验**（旧代码已删除，复现需回滚）；
   旧实现"每请求换桶"是由代码语义直接推得的（`split(",")[0]` 进桶键），非实测。
   要做对照，可用 `git stash` 回滚该文件重跑一次——列为可选后续。
2. 受信代理列表当前只支持精确 IP 与 CIDR，**不支持"按跳数信任"**（如"信任前 N 跳"）。
   这是有意的：跳数模型在多层 CDN 下容易配错，而配错的方向是"信任了不该信任的段"。
3. IPv4-mapped IPv6（`::ffff:127.0.0.1`）与纯 IPv4 视为**不同**地址；容器/双栈环境下若出现这种形态，
   需在受信列表里同时写上两种写法。这是刻意保守（宁可漏信任，不可错信任），已在解析器注释中说明。
4. 本批只改限流一处，未顺手改其他审计条目（AUD-4~7 见台账）。
