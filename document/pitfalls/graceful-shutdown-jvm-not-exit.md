# 坑位：优雅停机后 JVM 未退出（Windows 本地实测）

**现象**（S3.8 WP0 实测）
- `/actuator/shutdown` 返回成功，端口 8400 已释放，日志有 `Commencing/Graceful shutdown complete`；
- 但 `javaw` 进程仍存活（需 `Stop-Process` 才退出）。

**根因（推断，未做线程转储实证）**
- 上下文关闭只保证 Spring 生命周期与 Web/Scheduler 收尾；若存在**非守护线程**（如 MQ gRPC 客户端内部线程、
  本地 `Executors.newSingleThreadExecutor` 未在 @PreDestroy 关闭），JVM 不会自行退出。
- 本项目 sim 的发送线程为 daemon，平台 MQ 消费线程为 daemon；嫌疑集中在 rocketmq-client-java 的
  gRPC/telemetry 非守护线程。

**影响**
- 生产容器无需担心（编排层最终 SIGKILL）；本地脚本/CI 停机流程会被"进程假死"误导。

**处置建议（未实施，roadmap）**
1. 停机剧本以"端口释放 + 日志完成"为通过口径（当前 `_g8` 即如此），不要求 JVM 自退；
2. 若要 JVM 自退：对第三方客户端线程显式关闭（`ClientServiceProvider` 不暴露全局关闭时为库限制），
   或停机后由脚本兜底 kill；不要用 `System.exit` 硬杀掩盖未收尾组件。
