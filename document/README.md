# document/ —— battery-swap-ops 文档目录

> 组织原则（沿用工作区习惯）：**一问题一文件**。

| 目录 | 内容 | 命名 |
|---|---|---|
| `plans/` | 设计冻结与方案（S0.*） | `S{阶段}.{序}-{主题}.md` |
| `block-records/` | 批次实施记录（做了什么/取舍/验证/证据） | `批次N-{主题}.md` |
| `pitfalls/` | 踩坑记录（现象/根因/处置/教训） | 英文短横线主题名 |
| `fixes/` | 缺陷修复记录 | 英文短横线主题名 |
| `knowledge/` | 深层知识点 | 英文短横线主题名 |
| `roadmap/` | 待办（触发条件 + 方案） | 英文短横线主题名 |

## 当前清单

### plans（S0 设计冻结）
- plans/S0.1-领域模型.md
- plans/S0.2-状态机.md
- plans/S0.3-设备协议v1.md
- plans/S0.4-API清单.md
- plans/S0.5-表清单与约束.md
- plans/S0.6-演示剧本.md
- plans/S0-自查优化记录.md
- plans/S3-可靠性深水-方案.md（基于 S2 后代码盘点的 S3 实施定稿）
- plans/S7-运营纵深-方案.md（S7 A/B/C/D + WP-0 实施方案 v2；含子 agent 审查修订与拍板口径）
- plans/S8-前端管理台与BFF视图层-方案.md（设计冻结：“多接口顺序调用要不要编排”定论 / BFF 同进程取舍 / VO 与能力位 / 权限方案 / 页面×数据依赖矩阵 / 批次29-31）

### block-records
- block-records/批次1-骨架与指令闭环.md
- block-records/批次1-复审与优化.md
- block-records/批次2-换电闭环.md
- block-records/批次3-可靠性深水.md（S3；batch3 剧本 _g4/_g5/_g7 PASS、_g6 部分通过，详见记录）
- block-records/批次4-S3.8生产化加固.md（S3.8；含标准 vs 降级自查、同步阻塞反思、遗留项）
- block-records/批次5-S4运营与调度.md（S4-pre/S4.4工单/S4.5看板+CRUD；含实战发现与边界）
- block-records/批次6-S4.1-S4.6.md（S4 纵深：电池健康/调拨/充电策略/Agent 接缝；S4 完成）
- block-records/批次7-S5质量交付与项目审查.md（覆盖率门槛/JMeter/GC/全项目审查 P1×4 修复/S5.4 文档收口）
- block-records/批次8-S5业务审查与修复.md（业务逻辑逐域深审：跨用户幂等键/OVERDUE 无出口 2×P1 + 6×P2 + 3×P3，305 测试）
- block-records/批次9-S5运维审查与预案.md（慢 SQL 审计→db/10 索引、8 类边界极限故障预案、运维缺口修复、_c16 剧本 16/16）
- block-records/批次10-S7-WP0-WPA.md（押金二次退款修复 + 管理端 RBAC/审计移植：4 角色 38 权限码、_c17 18/18、327 单测）
- block-records/批次11-S7-WP-C.md（渠道对账 T+1：账单导入幂等/四类差异/处置留痕/每日任务，_c19 10/10、335 单测）
- block-records/批次12-S7-WP-D.md（用户服务：报障→工单/欠费闭环/优惠券/站内信，_c20 15/15、356 单测）
- block-records/批次13-S7-WP-B.md（代理分润结算：分账流水/冲正/补行/结算单状态机，_c18 12/12、375 单测）
- block-records/批次14-S7收口.md（全量回归 _g1+_c16-_c20 全 PASS；S7 交付面与声明总表）
- block-records/批次15-P0-4演示入口与复现门槛.md（OpenAPI/一键演示/compose/第三方契约客户端/sim 重置端点）
- block-records/批次16-P0-3a-b-c读路径容量与方法论.md（读路径复测 3230~3526/s、资源画像、量级换算）
- block-records/批次17-P0-3d-e写路径容量与JVM故事.md（写路径零超卖/计费恰一次、JVM 96m vs 512m；台账孤占根因复核）
- block-records/批次18-dev复位收敛与台账孤占归零.md（两阶段 reset 收敛修复/T2 孤儿柜退役/batch18 防回归剧本/MQ 环境整备；对账 18→0、全量 378/378）
- block-records/批次19-P1-10混沌演练与演练驱动缺陷修复.md（混沌 3 剧本 PASS：Redis/MySQL/broker 停机；演练驱动 3 缺陷修复 + workerId 租约稳定化；单测 347/347）
- block-records/批次20-P1-6可观测性.md（Prometheus 6 业务指标 + 401 鉴权 + traceId 日志 pattern + Grafana 面板 JSON；_c24 9/9 PASS、单测 349/349）
- block-records/批次21-P1-7参数校验与P1-11告警webhook.md（校验统一 400 + 出站 webhook 签名/重试/熔断/隔离；_c25 13/13、_c26 12/12 PASS、单测 356/356）
- block-records/批次22-P1-8数据权限.md（DataFilter：按站点隔离的管理端数据范围——六类列表过滤 + 资源级 403 + fail-closed + 自检切面；_c27 24/24 PASS、单测 371/371）
- block-records/批次23-P0-1集成测试进CI.md（Testcontainers 容器基座 + IT×3 换电主链路/对账检出/并发零超卖 + failsafe；CI 首跑绿、门禁红探针实证、本地无 Docker 371/371 不受影响）
- block-records/批次24-P0-2分片保序与双实例演练.md（FIFO message group spike：含"服务端不 hold 未 ack 同柜后续"反直觉结论；两端分片改造；`_c28` 15/15；双实例 30 分钟 `_c29` 18/18 零重复执行；保序口径"每柜单调"）
- block-records/批次25-题库-ADR-故事卡.md（P0-5/P1-9/P2-12 三份访谈就绪文档；纯文档）
- block-records/批次26-P2-13静态检查与异构设备端.md（SpotBugs 门禁首跑抓真 bug；Python 异构设备端 `_c30` 25/25；下行 chunked / 设备会话关联 / PS 管道三坑）
- block-records/批次27-S6运维Agent最小版.md（swap-agent 模块（零依赖外部消费者）+ 规则引擎 14 类型映射/克制原则 + 评测集 20/20 + `_c31` 31/31（含反向断言：杀 Agent 平台无恙）；P2-11）
- block-records/批次29-S8前端地基与BFF视图层.md（S8 第1批：`auth/me` + `ActionsSupport` 能力位 + db/17 工单站点归属 + 7 个 `/admin/view/**` BFF 聚合 + 权限码前后端契约门禁；`_c32` 54/54、单测 473/473）
- block-records/批次30-S8前端骨架与分页缺陷修复.md（S8 第2批：`swap-web` 入仓 24 文件（路由守卫/`v-access`/登录·看板·告警与建议单）+ CI frontend job + `_c33` 81/81；**实机联调抓出存量缺陷：缺分页拦截器导致 13 端点假分页（op-log 单次返回 21491 行）**；单测 480/480 Skipped 0）
- block-records/2026-09-19-S8前端阶段-交接文档.md（**接手入口**：质量基线实测值 + 剩余工作四组（A 批次31 / **B 云上同步欠账：实测云上 jar 停在批次19、落后 11 批次含 4 个已修缺陷** / C 可选项 / D 文档欠账）+ 坑位速查 + 待拍板决策点 + 文件地图）
- block-records/2026-09-16-1856-写路径容量模块-交接文档.md（模块暂停交接书；其 §7 执行序已由批次17 收口）

### knowledge（节选：入口性文档）
- knowledge/architecture.md（架构全景/组件机制/安全资金边界/部署形态）
- knowledge/runbook.md（中间件/启动器矩阵/通道矩阵/排障索引/云端占位）
- knowledge/ops-troubleshooting.md（慢 SQL 治理 + 8 类边界极限故障预案，S5 运维审查）
- knowledge/rbac-and-audit.md（管理端身份/权限/审计：角色矩阵、break-glass、覆盖性守卫 + P1-8 数据范围（站点隔离）；S7 WP-A）
- knowledge/channel-recon.md（渠道对账 T+1：模型/流程/差异分类/证据/边界，S7 WP-C）
- knowledge/user-service-and-coupon.md（报障/欠费/券/站内信：状态机、分账口径、证据，S7 WP-D）
- knowledge/agent-settlement.md（代理分润结算：基数口径/冲正/结算单/守恒，S7 WP-B）
- knowledge/decision-records.md（ADR 10 条 + 15 分钟白板讲述顺序；P1-9，批次25）
- knowledge/story-cards.md（6 张缺陷故事卡 + 边界与未做清单 12 项；P2-12，批次25）
- knowledge/agent-seam.md（Agent 接缝：两段式动作/MCP 工具清单 + S6 最小版落地（swap-agent 模块/规则表/评测集/剧本）；S4.6 + 批次27）
- fixes/resilience-patch.md（韧性补丁 G1-G5：计费欠费化/报障复核/对账互斥，S7）
- knowledge/s5-quality-delivery.md（S5 质量线：压测/GC/覆盖率设计与结论）
- 其余为专题档（outbox/支付仲裁/熔断/缓存/策略/调拨/Agent 接缝等），见目录。

### 剧本证据
- `scripts/verify/README.md`（37 剧本总索引 + 前置 + 命名约定；`_out.txt` 为统计证据，原件归档 diag-archive/）

### pitfalls
- pitfalls/gbk-source-encoding.md
- pitfalls/graceful-shutdown-jvm-not-exit.md
- pitfalls/mp-lambda-cache-test-order.md
- pitfalls/mp-updatebyid-ignores-null.md
- pitfalls/ps-utf8-bom-and-mojibake.md（PS 5.1 BOM/GBK 坑集；§5 批次21 补"必须断言中文→UTF-8 with BOM"正解 + `-OutFile` StatusCode 坑）
- pitfalls/spring-wiring-traps-not-covered-by-unit-tests.md
- pitfalls/dev-reset-nonconvergent-orphans.md（dev reset 不收敛 + 孤儿柜种子缺失 → 台账孤占残留；**已修复·已归零**，批次18）
- pitfalls/mq-store-rebuild-topic-recovery.md（RocketMQ store 重建后 topic 丢失 + proxy 40014 消息类型校验；已处置·dev 口径）
- pitfalls/redis-fault-cascade-no-timeout.md（Redis 无超时级联拖死全站；已修复，批次19）
- pitfalls/redis-data-loss-runtime-consistency.md（Redis 数据回退的运行时一致性三连坑 + 恢复清单；已修复，批次19）
- pitfalls/micrometer-gauge-weak-ref-flaky.md（Gauge 弱引用 + 测试无强引用 → 全量偶发 NaN；已修复，批次21）
- pitfalls/ps-function-return-unroll-count.md（PS 函数返回展开：`.Count` 0/1/N 三态假 FAIL/假 PASS；批次21 收敛、批次22 剧本再现并改为调用处 `@()` 全量覆盖）
- pitfalls/downlink-chunked-empty-body-minimal-stack.md（JDK 客户端默认 chunked+h2c → 最小设备栈读空 body；已修复+单测门禁，批次26）
- pitfalls/device-session-seq-missing.md（事件未回带开门会话 commandSeq → 事件到达但订单不推进；已修复，批次26）
- pitfalls/ps-pipe-blocks-on-spawned-jvm.md（PS 管道接壳长活 JVM → `| Out-Null` 永不返回；已修复，批次26）
- pitfalls/mp-pagination-interceptor-missing.md（**缺 `PaginationInnerInterceptor` → `selectPage` 静默退化为全表查询且 `total` 恒为 0**；13 端点受影响、四层防线（单测 mock/剧本断言口径/SpotBugs/jacoco）全部抓不到；由前端实机联调暴露，已修复 + 两层回归网，批次30）

### roadmap
- roadmap/P0-P2-面试就绪补强计划.md（P0-P2 全部条目状态与执行顺序、变更记录）
- roadmap/面试自测-题库.md（30 题/8 类 + 自测协议；P0-5，批次25）

### 外部关联（工作区根，不入本仓库）
- `项目一-换电运营平台-设计备忘.md`（项目定位/架构/分期）
- `项目一-阶段计划.md`（S0-S5 阶段与小阶段门禁）
- `项目一-参考基准.md`（成熟项目/协议/标准参照与取舍）
