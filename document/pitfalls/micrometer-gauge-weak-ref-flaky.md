# 坑位：Micrometer Gauge 弱引用 + 测试无强引用 → 全量测试偶发 NaN（flaky）

> 状态：已修复（2026-09-16，批次21）| 证据：`mvn -pl swap-server -am test` 356 测试中出现 1 例偶发失败

## 现象

- `SwapMetricsTest.指标注册与取值` 报 `expected: 3.0 but was: NaN`；
- 单独跑该类**必绿**、批次20 当时全量也绿（CI 亦绿）——典型的"全量偶发 + 单跑正常"flaky；
- 首次复跑 356 全量时再次出现，位置固定（第一个断言 `swap.outbox.backlog`）。

## 根因链

1. Micrometer `Gauge.builder(name, obj, fn)` 对 `obj` 默认**弱引用**持有（允许对象可回收时自动注销 meter）；
2. 测试 `new SwapMetrics(registry, ...)` 后**未保存引用**——生产由 Spring 容器持有单例，测试没有；
3. 全量 356 测试的内存压力触发 GC → SwapMetrics 实例被回收 → `Gauge.value()` 对空引用返回 **NaN**（不是异常、不是启动失败）；
4. 指标名注册齐全、无任何报错——**静默错误值**，最坏的一类。

## 修复（双保险）

- 生产：`SwapMetrics.registerGauges()` 全部 `Gauge.builder(...).strongReference(true)`
  （单例生命周期=应用，弱引用语义本无收益）；
- 测试：`SwapMetrics metrics` 字段持有实例（模拟容器持有），注释写明原因；
- 复跑 356 全量：绿；后续再跑仍绿（GC 不再能回收）。

## 可复用教训

- **注册成功 ≠ 值正确**：Gauge/弱引用型指标在"被观测对象生命周期短于 registry"时静默 NaN——排障时先看值，不要只看注册；
- 间歇性 flaky（单跑绿/全量偶红/CI 绿）优先怀疑 **GC 不确定性 + 弱引用/缓存**，而非业务逻辑；
- Micrometer 三选一：`strongReference(true)` / 调用方持引用 / 接受可回收——**测试环境必须显式做前两者之一**，否则红灯出现时机由 JVM 心情决定。
