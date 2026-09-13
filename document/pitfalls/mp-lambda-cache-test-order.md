# 坑位：MyBatis-Plus lambda 缓存是全局静态 + 测试执行顺序依赖（本地绿 CI 红）

**现象**（2026-09-13，S4.5 自检修复推送后）：
本地 `mvn clean test` 257/257 全绿；CI 同一提交红：
`MybatisPlus can not find lambda cache for this entity [PlanEntity]`（PlanServiceTest.管理端更新）。

**根因**：
1. `LambdaQueryWrapper`/`LambdaUpdateWrapper` 的方法引用需要 `TableInfoHelper` 里的实体映射；
2. `TableInfoHelper` 是**JVM 级静态缓存**——只要本 JVM 内任一测试/代码初始化过该实体，后续测试都能用；
3. 本地跑时恰好有别的测试先初始化了 `PlanEntity`（顺序侥幸），CI 的测试顺序不同 → 暴露缺失；
4. 我的新 `updatePlan` 从 `updateById` 改为 `LambdaUpdateWrapper` 后，`PlanServiceTest` 的 `@BeforeAll`
   只初始化了 `UserPlanEntity`——缺口就此埋下。

**处置**：
- `PlanServiceTest` @BeforeAll 补 `PlanEntity`；`UserAccountServiceTest` 预补 `SwapUserEntity`（防未来踩）；
- **引入顺序验证**：`mvn -B -ntp test -Dsurefire.runOrder=random` 作为提交前的补充检查（本次随机顺序全绿）。

**教训/检查项**：
1. 用 LambdaWrapper 的测试类，`@BeforeAll` 必须为**所有**涉及的实体 `initTableInfo`（不要依赖别人先跑）；
2. "本地绿"不证明测试无顺序依赖——涉及静态缓存/共享状态的测试，push 前跑一次 random 顺序；
3. 自检不只是读代码：**跑一把随机顺序**能抓到代码审阅看不见的隐性依赖。
