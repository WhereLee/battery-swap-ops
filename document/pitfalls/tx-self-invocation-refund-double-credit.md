# 坑位：@Transactional 自调用静默失效 → 退款可双记（批次32）

> 类型：资金一致性缺陷（P0）｜发现方式：**独立代码审计**（2026-09-19，只读 subagent，313 条面经调研同期进行）
> 影响面：退款资金路径｜状态：**已修复 + 已建两层回归网**（单测断言事务边界 + 字节码级守卫 + CI 内故障注入 IT）

## 现象（可观测的后果）

同一张退款单可能向用户钱包**入账两次**；且事后从数据上看"一切都对"：

- `refund_record.status = SUCCESS`（第二次 CAS 成功）；
- 钱包余额比应付多出恰好一笔退款额；
- `refundableAmount()` 以 `refund_record` 扣减，它认为这笔已退 ⇒ **对账口径也发现不了**。

触发条件（任一）：
1. 入余额已提交、CAS 之前**进程被杀/崩溃** → 重启后延迟重投再次入余额；
2. 入余额与 CAS 之间**任一步抛异常**（`payment_record` 写入遇死锁 1213、连接抖动）；
3. 并发双执行，败者的 CAS 抛错——但**没有事务可回滚**已提交的加款。

单线程路径 2 即可复现，不需要并发。

## 根因

```java
public RefundRecordEntity refund(...) {
    return deadlockRetryExecutor.execute(() -> doRefund(...));   // 入口，无事务
}

private RefundRecordEntity doRefund(...) {                       // 私有
    ...
    try {
        apply(record);                                            // ← this 自调用
    } catch (RuntimeException e) {
        enqueueRetry(record.getRefundNo());                        // 失败被吞成"延迟重投"
    }
}

@Transactional                                                    // ← 自调用时完全不生效
public void apply(RefundRecordEntity record) {
    walletService.addBalance(...);              // 自动提交：钱已经出去了
    paymentRecordService.record(...);
    int rows = refundRecordDao.update(... WAIT→SUCCESS ...);       // CAS：闸门
    if (rows == 0) throw new IllegalStateException("CAS 冲突");
}
```

Spring 的 `@Transactional` 依赖代理：**同类内部 `this.apply(...)` 不经过代理，注解静默失效**。
于是设计意图里的"资金动作 + CAS 原子"根本不存在——只有一行注解在"看起来对"。

**关键不对称证据**：同一个 `apply` 还有第二条入口 `RefundApplyHandler`（延迟重投）——
它通过 `refundService.apply(record)` 走代理、**带事务**。两条入口事务语义不一致，这本身就说明
"apply 必须有事务"是设计意图，而在线路径的实现没兑现它。

## 为什么四层防线全都没抓到

| 防线 | 为什么看不见 |
|---|---|
| 单测 | `RefundServiceTest` 全 Mock：Mock 看不出代理，事务有没有开都一样过 |
| SpotBugs | 静态字节码规则不建模 Spring 代理语义 |
| 覆盖率 | `apply` 的每一行**都被执行过**——覆盖率只看"跑没跑到"，不看"事务在不在" |
| 剧本/IT | 只在**失败注入**下才显形；当时的剧本都跑在成功路径上 |

## 修复（批次32）

1. **事务改为编程式**（`TransactionTemplate`，注入 `PlatformTransactionManager`），
   `apply` 只做编排：`transactionTemplate.executeWithoutResult(status -> applyMoney(record))`。
   编程式事务对"谁来调"不敏感——在线退款与延迟重投语义就此一致，也不需要自注入/拆 bean 这类绕法。
2. **事务内只放资金关键三步**：入余额 → 记 REFUND 流水 → CAS `WAIT→SUCCESS`。
   任何一步失败整体回滚，退款单保持 WAIT（**可重驱动**，不产生二次入账）。
3. **站内信与分账冲正移到事务外**，各自 best-effort：
   S7 WP-D/§WP-B 的声明是"写失败不影响退款"；放进事务会让一封站内信把退款一起回滚，与声明相反。
4. **WAIT 不再是终局**：`doRefund` 命中既有单时，若状态是 WAIT 则显式重驱动
   （旧实现直接 `return` → 单子永久悬空：用户拿不到钱，而 `refundableAmount` 已把它算作已退）。
   管理端重复发起、补偿任务轮询、延迟重投三条路径因此都能自愈。

## 回归网（两层 + 一条可红性证据）

| 层 | 文件 | 守什么 |
|---|---|---|
| 单测（本地/CI 都跑） | `RefundServiceTest` 新增 4 例 | 成功→**提交**；CAS 冲突→**回滚**且单留 WAIT；WAIT 可重驱动；SUCCESS 终态不再入账 |
| 字节码守卫（本地/CI 都跑） | `TransactionalSelfInvocationGuardTest` | **全仓扫描**：非事务方法自调用 `@Transactional` 方法一律判违规（含内部类↔外层类）；含探针自检证明规则不空转、且不过宽（事务方法调事务方法不报） |
| 故障注入 IT（CI 必跑） | `it/RefundAtomicityIT` | 真 MySQL 事务 + 真钱包：在加款之后注入失败 → **断言余额一分未动**，单留 WAIT；移除故障重驱动 → 恰好入账一次；重复 apply 不再入账 |
| 门禁可红性 | `scripts/verify/batch32/_g32_out.txt` | 把 `@Transactional` 加回 `applyMoney`（复原缺陷形状）→ 守卫 **BUILD FAILURE**；还原 → **BUILD SUCCESS** |

> 守卫的判定规则刻意收窄：**只在"调用方自己不是事务方法"时报**。
> 反例（已核对、判为良性）：`ChannelReconService#importBill`（事务）调用 `reconcile`（事务）——
> 被调方即使走 `this` 也仍在调用方的事务里，REQUIRED 语义等价，不该报。

## 经验（可复用）

1. **"注解在不在"不等于"机制在不在"**：`@Transactional`、`@Cacheable`、`@Async`、`@Valid`（方法级）、
   `@PreAuthorize`（同类调用）——凡是靠代理生效的注解，自调用一律失效，且**全部静默**。
2. **资金动作与状态闸门必须同一事务**；顺序上"先 CAS 再动钱"和"同事务内先动钱再 CAS"都成立，
   唯独"先动钱自动提交、再 CAS"不成立——它把闸门放在了钱后面。
3. **中间态要有出口**：`WAIT` 这类中间态如果没有任何重驱动路径，等价于资金黑洞；
   有中间态就必须回答"谁来把它推走"。
4. **可测性反推设计**：这条缺陷能被 IT 抓住，是因为修复后事务边界成了可观测行为
   （提交/回滚/余额），而不是一句注解。
5. **审计与自测的分工**：这条由**独立只读审计**发现，而项目的单测/SpotBugs/覆盖率/剧本四层都没抓到
   ——"多一双不懂上下文的眼睛"本身是一种防线。
