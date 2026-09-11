# 知识：线程池与连接池的基线（S3.8 WP4）

> 代码/配置：`ThreadPoolConfig`（applicationTaskExecutor）、`application.yml`（Hikari/Tomcat/调度池）。
> 测试：`ContextLoadsTest`（无外部依赖的装配冒烟）、`ThreadPoolConfigTest`（有界/CallerRuns/MDC）。

## 1. 池基线（本项目取值与理由）

| 池 | 取值 | 理由 |
|---|---|---|
| Tomcat 线程 | max 100 / min-spare 10 | 请求并发上限；有界，超过则 accept-count 排队 |
| 应用线程池（swap-async-） | core 8 / max 16 / queue 200 | 异步任务隔离；有界队列 + CallerRuns |
| DB 连接池（Hikari） | max 20 / min-idle 5 / 获取超时 2s | 拿不到连接快速失败，不无限排队拖请求 |
| 调度池（@Scheduled） | 4 | 原先默认 1 线程：对账/扫描/看护会互相阻塞（单线程互相饿死） |

**递减关系**：Tomcat(100) > 应用池(16)+调度(4) ≥ DB(20)。下游池小于上游，压力最终以"背压/快速失败"暴露，
而不是把数据库打爆形成雪崩链（连接池耗尽 → 请求全挂）。

## 2. 拒绝策略为什么选 CallerRuns（而不是 Abort/Discard）

- **Abort**：抛 RejectedExecutionException → 任务直接丢，业务静默失败；
- **Discard/DiscardOldest**：静默丢任务，问题不可见；
- **CallerRuns**：由提交任务的线程执行 → 上游被拖慢（背压），任务**不丢**且问题立刻可感知。
  适合"任务必须执行"的运维/资金相关异步（本项目所有异步都属于此类）。

## 3. 细节

- **MDC traceId 传递**：`TaskDecorator` 把提交线程的 traceId 带进异步任务（MDC 不自动跨线程），否则异步日志断链；
- **指标**：`ExecutorServiceMetrics.monitor(...)` 绑定 Micrometer（`swap.application.executor.*`），与 Tomcat/Hikari 指标同看板定位瓶颈；
- **Hikari**：`connection-timeout=2s`（快速失败）+ `leak-detection-threshold=20s`（连接泄漏打点）；
- **优雅停机**：`applicationTaskExecutor` 等待收尾 20s（与 WP0 的 graceful 配置一致）。

## 4. 装配冒烟（ContextLoadsTest）

- 用测试属性绕开外部依赖：显式 workerId（免 Redis 租约）、关缓存监听/MQ 消费、Hikari 不预热、假密钥；
- 目的：把"单测全绿但容器起不来"（S3.8 连踩两次的装配坑）提前到 CI；
- CI 无 MySQL/Redis 也能跑，属于"最小成本的最大保险"。

## 5. 已知边界

- 未做真实"连接池占满"的混沌剧本（会污染本地库连接配额）；饱和行为由池配置 + 单测语义保证，
  Hikari/Tomcat/Micrometer 指标可在压测阶段（S5 JMeter）观测——如实声明。
- 应用池目前承载的异步任务较少（多为 @Scheduled 与内部线程）；本项主要是**基线**防未来滥用。
