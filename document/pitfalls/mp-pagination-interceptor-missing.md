# 坑位：MyBatis-Plus 缺分页拦截器 —— `selectPage` 静默退化为全表查询且 total 恒为 0

**现象**（S8 批次30，前端管理台首次实机联调）：
浏览器打开告警页，请求 `/admin/view/alarm?page=1&limit=5`，**实际返回 3854 行**（整张 `alarm` 表），
响应里 `total=0`、`page`/`limit` 字段却回显正确。前端因此显示"共 0 条"、表格渲染数百行、翻页按钮全禁用。

**根因**：
MyBatis-Plus 的 `selectPage(new Page<>(page, limit), wrapper)` **只声明分页意图**，
真正的 `LIMIT` 与 `COUNT(*)` 由 `PaginationInnerInterceptor` 在 SQL 执行期改写注入。
本项目从未注册该拦截器（`config/` 下只有 Web/Cache/ThreadPool/AdminSecurity 四个配置类），于是：

- 不拼 `LIMIT` → 全表返回；
- 不跑 count → `IPage.getTotal()` 恒为 0；
- **全程不抛异常、不告警**，`PageResult.of(...)` 照常组装，HTTP 照常 200。

**危害量化**（修复前实测，本地库）：

| 端点 | 表实际行数 | 修复前每次列表请求返回 |
|---|---|---|
| `/admin/account/op-log` | 21491 | 21491 行全表序列化 |
| `/admin/order` | 5842 | 5842 行 |
| `/admin/view/alarm` | 3856 | 3856 行 |
| `/admin/cell` | 120 | 120 行 |

共 **13 个分页端点、10 处 `selectPage` 调用**全部受影响（订单/站点/柜/仓/电池/用户/工单/调拨/建议单/审计日志/告警视图/建议单视图/工单视图）。
审计日志与订单表只增不减，等于**列表接口的响应体随运行时间线性膨胀**——这是拖库级别的缺陷，不是"体验问题"。

**标准处置**：
`config/MybatisPlusConfig.java` 注册拦截器（顺序按官方建议：改写 SQL 的分页在前，纯校验的防全表写在后）：

```java
MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
pagination.setMaxLimit((long) PageParams.MAX_LIMIT);  // 200
pagination.setOverflow(false);
interceptor.addInnerInterceptor(pagination);
interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
```

两个参数的理由：

- `maxLimit=200`：**第二道防线**。`PageParams.limit()` 已在应用层钳制，但 `SwapOrderService` /
  `AssetAdminService` 是直接 `new Page<>(pageNum, size)` 绕过它的，只有拦截器层能兜住"翻大页拖库"面。
- `overflow=false`：页码越界返回**空列表**而非静默回到首页，否则前端翻页会反复看到第一页数据却以为到了末页。

`BlockAttackInnerInterceptor`（拦截无 where 的全表 update/delete）加之前已核实：
全仓 `update(null, wrapper)` / `delete(wrapper)` 调用**均带 `.eq(...)` 条件**，无合法全表写，故只拦事故不拦业务。

**为什么四层防线全都没抓到**（这才是本坑位真正的教训）：

| 防线 | 为什么失效 |
|---|---|
| 单元测试（473 个） | 全部 mock `dao.selectPage(...)` 返回手工构造的 `Page`，**不经过 SQL 执行链**，拦截器在不在都一样 |
| 剧本 `_c0`-`_c32` | 断言口径是"`code=0` + 字段存在 + 业务值正确"，**从未断言 `list.size() <= limit`**；且多数表当时只有十几行，全表返回也"看起来对" |
| SpotBugs / 编译期检查 | 装配缺失属运行时容器行为，静态分析看不见 |
| jacoco 覆盖率 | 覆盖率高≠正确；`selectPage` 那行**被执行了**，只是执行的语义不对 |

**结论：凡是"依赖容器/拦截器/字节码增强才生效"的机制，必须有一条真跑 SQL 或真起容器的断言。**

**回归网（本次同时建立）**：

1. `MybatisPlusConfigTest`（7 测）：把装配本身钉死——分页拦截器存在、方言为 MySQL、
   `maxLimit == PageParams.MAX_LIMIT`、`overflow=false`、防全表写存在、两者顺序正确。
   配置被误删即在单测阶段变红（这是唯一能在 CI 里挡住回归的一层，因为 CI 不连 DB）。
2. 剧本 `scripts/verify/batch30/_c33_paging.ps1`：对 openapi.json 里全部 **13 个**分页端点各断言
   `limit` 生效 / `total >= rows` / **不出现"有行但 total=0"的静默特征** / 第 2 页首行与第 1 页不同 /
   `limit=100000` 被封顶到 200。实测 **81 PASS / 0 FAIL / 1 SKIP**
   （SKIP=`/admin/transfer` 表为空，行数不足以证明 offset，已如实标注而非硬凑）。

**同类风险自查**（"声明了但需要拦截器/增强才生效"的机制）：
`@Version`（乐观锁，需 `OptimisticLockerInnerInterceptor`）、`@TableLogic`（逻辑删除）、
`@EnumValue`（枚举映射）、多租户（`TenantLineInnerInterceptor`）——
全仓 grep **零匹配**：本项目乐观锁一律走显式 CAS 条件 UPDATE（`cas(...)` + `.eq(status, from)`），
逻辑删除与枚举映射均未使用。故**分页是唯一一个此类机制，不存在第二处静默失效**。
后续若引入上述任一注解，必须同步注册对应拦截器并补装配测试。

**记录**：由 `swap-web` 首次浏览器实机联调抓到（`vue-tsc` 与 `vite build` 全绿也挡不住——
类型和构建只证明前端调用形状正确，证明不了后端 SQL 语义）。
这正好实证了 S8 方案 §1 的判断："前端一旦接入，会立即暴露后端契约问题"——
只是暴露出来的比预想的更严重（预想是"字段靠猜"，实际是"分页机制整体缺失"）。
