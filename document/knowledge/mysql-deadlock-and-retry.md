# 知识：MySQL 死锁与标准重试（S3.8 WP7）

> 代码：`DeadlockRetryExecutor`（spring-retry 3 次/指数退避/仅死锁类异常）+ `RefundService.refund` 接入。
> 证据：`scripts/verify/batch4/_c5_deadlock.ps1`（反序锁→1213；同序锁→0 死锁；InnoDB 日志可查）。

## 1. 死锁是什么（先摆正认知）

- InnoDB 检测到循环等待时，**主动回滚"代价小"的一方**并报 `ERROR 1213 Deadlock found`；
  另一笔正常提交。这是**正常的并发控制行为**，不是系统"坏了"。
- 与 `1205 Lock wait timeout` 区分：1205 是等待超时（可能只是慢），1213 才是死锁被选中牺牲。
- 应用侧正确姿势 = **一致加锁顺序 + 幂等动作 + 有界重试**；把 1213 当致命错误报警/回滚整个流程是反模式。

## 2. 常见成因

| 成因 | 典型场景 | 对策 |
|---|---|---|
| 加锁顺序相反 | 事务 A：行1→行2；事务 B：行2→行1 | **统一顺序**（按主键/业务键排序后再更新） |
| 无索引范围更新 | `UPDATE ... WHERE 非索引列` 全表锁/间隙锁 | 覆盖索引 + 缩小范围 |
| 唯一键并发插入 | 两个事务插同一唯一键（S 锁互等） | 捕获 DuplicateKey 幂等处理（本项目已做） |
| 大事务 | 事务内做 RPC/长逻辑 | 短事务 + 事务外网络调用（本项目已做） |

## 3. 诊断入口

1. 应用日志/异常：`DeadlockLoserDataAccessException`（Spring 对 1213 的映射）、`CannotAcquireLockException`（1205）；
2. `SHOW ENGINE INNODB STATUS\G` → `LATEST DETECTED DEADLOCK`（含两个事务的 SQL、持有/等待锁）；
3. 长期观察：`innodb_print_all_deadlocks=ON` 落错误日志；
4. 复现脚本：`_c5_deadlock.ps1`（反序/同序对照）。

## 4. 标准重试件（本项目实现）

```
DeadlockRetryExecutor: maxAttempts=3, exp backoff 100ms×2 (cap 1s)
  retryOn: DeadlockLoserDataAccessException(1213) / CannotAcquireLockException(1205)
  不 retry: 业务异常/校验失败
前提: 动作幂等（唯一键/CAS/条件更新）——本项目退款/指令/事件落库均满足
```

接入点：`RefundService.refund`（补偿任务与人工退款可能并发触发同订单流水写入，是真实的死锁候选路径）。

## 5. 证据（实测）

- 反序两事务：sessionB 回滚 1213（PASS）；
- 同序两事务：两端无 1213（PASS，"统一顺序"是首选修复，重试只是兜底）；
- `SHOW ENGINE INNODB STATUS` 可查到 `LATEST DETECTED DEADLOCK`（PASS）。

## 6. 边界（如实声明）

- 重试只接入了退款路径，不是全局 AOP（避免把"业务缺陷"用重试掩盖）；
- 未实现死锁指标上报（InnoDB 状态轮询/告警）——S5 可观测阶段再补；
- 重试是最后防线，**首选仍是一致加锁顺序与短事务**。
