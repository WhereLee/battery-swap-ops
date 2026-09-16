# Redis 故障级联：客户端无超时拖死全站（P1-10 混沌发现）

> 状态：已修复（2026-09-16，批次19）| 证据：`scripts/verify/batch19/_c21_out.txt`

## 现象（首跑实测）

kill Redis 后约 5 秒内：
- 平台 HTTP 层 `CONN_REFUSED`（新连接被拒）；
- 用户请求 60s 超时无响应（客户端已放弃，服务端线程仍阻塞）；
- 纯 DB 路径（admin reconcile）一并不可用——"Redis 依赖功能"的故障扩散为"全站瘫痪"。

## 根因链

1. `spring.data.redis` 只配 host/port/db，**未配超时** → Lettuce 默认命令超时 60s；
2. Redis 消失后，调度线程（看护/延迟轮询/扫描，秒级节拍）与请求线程**集体阻塞**在 Redis 调用上；
3. 100 个 Tomcat 线程 + accept 队列被占满 → 新连接被拒（TCP 层表现 = `CONN_REFUSED`）。

## 修复与验证

- `connect-timeout: 1s` + `timeout: 2s`（`application.yml`）；
- 复跑实测：health 即时返回 503、下单 2s 内 500 快速失败、停机期间 admin reconcile（DB 路径）可用、恢复后 rebuild-alloc 即回归。

## 可复用教训

- 故障域控制中，**依赖的超时参数与熔断同等重要**：无超时的依赖 = 能把调用方线程池整体拖死的依赖；
- 混沌验收标准不是"依赖恢复后系统恢复"，而是"**依赖不可用期间系统保持可响应（快速失败）**"；
- 排查入口：故障时"HTTP 层连不上"先怀疑**线程池被阻塞依赖占满**，而不是网络/端口问题。
