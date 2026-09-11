# 知识：JVM 并发坑位与本项目对应（S3.8 WP9）

> 可运行演示：`ConcurrencyPitfallsDemoTest`（ABA / ThreadLocal 串号 / MDC 不跨线程 / 正确 DCL）。
> 不可确定性复现的坑（SimpleDateFormat、无界队列 OOM、CAS 自旋）在此说明，不写"碰运气"的测试。

## 1. ABA（CAS 的类型陷阱）

- 现象：值 A→B→A 后，`AtomicInteger.compareAndSet(A, X)` **会成功**——它只看值不看历史；
- 演示：测试证明普通 CAS 误判、`AtomicStampedReference` 用版本戳识别；
- **写测试时踩到的真坑**：`AtomicStampedReference` 用**引用相等**比较引用，Integer 要求落在 -128~127
  缓存区间才可靠；用 100→200→100 会因 200 自动装箱成不同对象导致 CAS 意外失败（首版测试即因此红了 CI）。
  生产启示：用 `AtomicStampedReference` 时引用类型应选不可变单例/自定义值对象，或直接用 `AtomicMarkableReference`+基本类型；
- 工程含义：状态机若允许"回到旧值"（如状态可逆），CAS 就不安全；本项目订单状态**只前向不可逆**，
  天然规避 ABA（这也是状态机设计的价值之一，不是巧合）。

## 2. ThreadLocal / MDC 串号（线程池最大暗坑）

- 线程池线程复用：`ThreadLocal` 不 `remove`，下一个请求会读到上一个请求的值；
- 演示：测试展示"忘记 remove 时的脏读"；
- 本项目对应：`UserContext` 在拦截器 `afterCompletion` 清理；`TraceIdFilter` finally 清理 MDC；
  `ThreadPoolConfig` 用 `TaskDecorator` 把 traceId 显式带入子线程（MDC 不自动继承，测试已验证）。

## 3. 双重检查锁（DCL）必须有 volatile

- 缺 `volatile` 时可能发布"半初始化对象"（指令重排：先赋值引用后写字段）；
- 演示：测试验证正确 DCL 的跨线程单例；
- 本项目对应：`MqEventReporter`/`DeviceDownlinkGuard` 的懒建生产者/注册表均 `volatile` + synchronized 二次检查。

## 4. SimpleDateFormat 非线程安全（文档级，不做并发断言测试）

- 共享 `SimpleDateFormat` 并发 `format/parse` 会得到错乱结果或抛 `NumberFormatException`；
- 正确做法：`DateTimeFormatter`（不可变）或每次局部变量（本项目用 `System.currentTimeMillis` + Jackson，不共享 SDF）；
- 不写并发测试的原因：失败不可稳定复现，会污染 CI（诚实地"知道并且规避"，比写 flaky 测试更专业）。

## 5. 线程池拒绝策略与无界队列

- `newFixedThreadPool` 用**无界队列**：任务无限堆积 → 内存耗尽前不报错（最危险的"静默失败"）；
- 本项目：`ThreadPoolConfig` 有界队列 200 + CallerRuns（背压），并有单测；
- 结论：生产禁止无界队列（除非有全局背压与容量评估）。

## 6. 其它一句话坑位（面试常问）

| 坑 | 一句话 |
|---|---|
| `submit()` 吞异常 | `Future` 不 get 就看拿不到异常；`execute()` 才会直接抛 |
| `InterruptedException` 被吞 | 停机信号失效；捕获后必须恢复中断位或上抛 |
| `wait()` 不用 while | 虚假唤醒 → 条件不成立也继续执行 |
| `HashMap` 并发扩容 | JDK7 成环 CPU 100%；并发结构用 `ConcurrentHashMap` |
| `Atomic` 高竞争 | CAS 自旋反而更慢；高竞争用 `LongAdder`（本项目计数均 LongAdder） |
| 锁顺序相反 | 跨锁死锁；能单行 CAS 就不上多锁（本项目原则） |
