# battery-swap-ops

两轮电动车换电柜运营平台（样例工程）：**从"设备可靠接入"到"设备经营"**——单城市多站点换电网络，
覆盖设备接入、换电订单闭环、可靠性工程与运营调度。

> 设计文档：`document/plans/`（S0 设计冻结全集）；架构详见 `document/knowledge/architecture.md`；
> 运行详见 `document/knowledge/runbook.md`；剧本索引 `scripts/verify/README.md`。
> 阶段计划见工作区 `项目一-阶段计划.md`；参考基准见工作区 `项目一-参考基准.md`。

## 现状（2026-09-19）

- 阶段：S0 设计冻结 ✅ / S1 指令闭环 ✅ / S2 换电闭环 ✅ / S3 可靠性深水 ✅ / S4 运营调度 ✅ /
  S5 质量与云交付 ✅ / S7 运营纵深 ✅（管理端 RBAC+审计 / 渠道对账 T+1 / 用户服务与营销 / 代理分润结算 / 韧性补丁）/
  **S6 运维 Agent 最小版 ✅**（独立 `swap-agent`：只读诊断 + 建议单闭环 + 评测集 20 题 + 反向断言）/
  **S8 前端管理台与 BFF 视图层 ✅**（批次29 后端地基 + 批次30 前端骨架 + **批次31 八个业务页与部署**：
  `/admin/auth/me` + 能力位 + **11 个 `/admin/view/**` 聚合** + `swap-web`（登录/看板/告警与建议单/**工单/订单/柜/结算**，
  路由守卫 + `v-access` + 后端能力位驱动按钮）；前端实机联调抓出并修复存量缺陷：缺分页拦截器导致 13 端点假分页）
- 测试：**520/520**（契约 4 + 平台 456 + 模拟器 31 + Agent 29，Skipped 0）；JaCoCo 门槛 server 65% / sim 55% / contract 70% / agent 65%，CI `mvn verify` 强制（实测 server 71.5% / agent 86.2%）；前端门禁 `vue-tsc --noEmit` + `vite build`（CI `frontend` job）；集成测试 `*IT` 由 failsafe 在 CI（Docker）跑，本地 `mvn test` 不受影响
- 对账不变量 **14 组**（含分账守恒/结算单一致/完成单必分账/欠费/券状态）；任务看护 9 项
- 容量（读路径方法论复测，512m 堆，限流关，同机）：**3,230/s @20 线程 / 3,526/s @100 线程，0 错误，p99 16ms/84ms**（预热+稳态窗口；旧 465.5/s 为压测端端口耗尽假象，见 `document/knowledge/capacity-model.md`）
- 实机剧本 54 个（`scripts/verify/README.md` 总索引；batch1-40 全 PASS，含双实例 `_c29`、异构设备端 `_c30`、运维 Agent `_c31`、BFF 视图与数据权限 `_c32`、分页回归网 `_c33`、页面级契约 `_c34`、**真实浏览器验收 `_c35`**、限流 XFF 绕过 `_c36`、**布局/对比度探针 `_c38`**、**索引/访问路径审计 `_c39`**）；另含两处**门禁可红性实证**（CI 集成测试红探针、事务自调用守卫红探针）
- 索引与访问路径（服务器侧实测，`_c39`）：用 `performance_schema` 逐语句统计审计真实流量 →
  修 3 处缺访问路径（`payment_record.order_id` 随 db/18 的唯一键被一起删掉、`alarm` 缺 `create_time`
  前导索引、"全部"页签全表排序、`swap_order` 缺 `(status, complete_time)`）→ **打流量读计数器差值**因果证明：
  **8,090 → 1 行/次**、**4,032 → 20 行/次**（均"无索引次数 +0"）；另含**无界读守类**（无 `WHERE`/`LIMIT`
  且每次返回 >500 行的 `SELECT`，30 分钟内出现即判红）
- 管理台可访问性（实机测量，`_c38`）：**10 条路由 1936 个文字元素 + 270 个标签对比度全部达标**
  （Element Plus 默认配色不达 WCAG AA：次要文字 2.87–3.08:1、主色 `#409eff` **2.78:1 双向失败**、
  标签 2.04–3.08:1、**占位符 2.30:1**；已做令牌级修正，标签区间 6.00–8.57:1、占位符 4.70:1）、
  89 个表头零错列、零裁切、零横向溢出；另覆盖**弹窗**（1 个实测 / 3 个因 `allowedActions` 缺失记 SKIP 带原因）
  与**非文本边界**（175 个 / 15 个低于 3:1，只报不改——修它属视觉设计决策）；
  截图 `.local/b31-shots/`（1440×900…2109 整页）

## 架构

```mermaid
flowchart LR
    SIM[swap-sim 换电柜模拟器<br/>N柜×M仓/故障注入/充电模拟] -->|事件 HTTP+HMAC / MQ 保序| SERVER
    MQ[[RocketMQ swap-device-event]] --> SERVER
    SERVER[swap-server 平台 :8400/api] -->|指令 commandSeq 幂等| SIM
    USER[骑手小程序端] --> SERVER
    ADMIN[运营后台<br/>RBAC: SUPER/OPS/FINANCE/SUPPORT] -->|"/admin/view/** BFF 聚合 + 能力位"| SERVER
    AGENT[swap-agent 运维 Agent<br/>只读+建议单 :8700] --> SERVER
    PAY[支付网关-模拟] --> SERVER
    SERVER --> DB[(MySQL 8 流水+状态机+分账)]
    SERVER --> RD[(Redis 缓存/限流/锁/雪花)]
    SIM --> MQ
```

可靠性组件：MQ 保序消费 / 幂等双线 / 定时对账（14 组不变量）/ 任务看护（9 任务）/ outbox / 延迟任务 /
限流 / 熔断舱壁 / 两级缓存 / 支付终态仲裁 / **计费硬失败欠费化（事件不回滚）**。
经营组件：**代理分润结算（append-only 分账+退款冲正）** / **渠道对账 T+1（四类差异+处置）** /
**用户服务（报障→工单 / 欠费闭环 / 优惠券 / 站内信）** / 工单 SLA / 看板 / 调拨 / 充电策略 / Agent 接缝。
前端接入层（S8）：**管理台 `swap-web`**（Vue 3 + Vite + TS + Element Plus；路由 `meta.codes` + `v-access` 双层权限、按钮由后端能力位驱动）、
**BFF 视图层 `web/view`**（一页一请求的只读聚合 + `allowedActions` 能力位 + VO 不出 Entity/不泄密钥）、
`GET /admin/auth/me`（角色+37 权限码+数据范围）、权限码前后端一致性门禁（`PermissionCodeContractTest`）。
同源部署（dev Vite proxy / prod nginx 反代 `/api`，`scripts/cloud/nginx-swap.conf`），**后端不开 CORS**。
管理台八页：运营看板 / 告警与建议单 / 工单（五步动作链）/ 换电订单（含退款·冲正通道）/ 换电柜（详情聚合五类数据）/ 结算单（confirm→paid）。
详见 `document/knowledge/architecture.md`。

## 模块

| 模块 | 说明 | 端口 |
|---|---|---|
| `swap-contract` | 双端契约：枚举 / HMAC canonical / 契约向量测试 | — |
| `swap-server` | 换电运营平台（设备接入 + 业务，context-path `/api`） | 8400 |
| `swap-sim` | 换电柜模拟器（N 柜 × M 仓，心跳/事件/故障注入） | 8500 |
| `swap-agent` | 运维 Agent（只读诊断 + 建议单；零依赖外部消费者） | 8700 |
| `swap-web` | 前端管理台（Vue 3 + Vite + TS + Element Plus + Pinia；登录/看板/告警与建议单已落地，详见 `swap-web/README.md`） | dev 5173 |

技术栈：Java 17 / Spring Boot 3.5 / MyBatis-Plus / MySQL 8 / Redis / RocketMQ / JMeter（容量）。

## 快速开始（本地，Windows PowerShell 5.1）

```powershell
# 1) 中间件：MySQL 3306(root/root)；Redis（--dir F:\Redis 保证 RDB 可写）；
#    RocketMQ namesrv 9876 + broker 10911 + proxy 8081（.local/start-broker-proxy.bat）
#    有 Docker 的机器可跳过本节：docker compose -f docker-compose.middleware.yml up -d（仅 MySQL+Redis）

# 2) 建库建表（幂等，db/00-19 共 20 个迁移脚本，按序执行）
mysql -uroot -proot < db/00-create-database.sql
mysql -uroot -proot < db/01-swap-schema.sql
# ... db/02-s2-migration.sql ~ db/19-index-audit-fixes.sql 依次执行

# 3) 密钥零明文：SWAP_DEV_SECRET / SWAP_ADMIN_TOKEN / SWAP_PAY_SECRET（.local/*.txt，gitignored）

# 4) 打包并启动（或 .local/run-server.bat / run-sim-dual.bat）
mvn -B -ntp package
.local\run-server.bat
.local\run-sim-dual.bat

# 5) 剧本 1（开仓闭环）
powershell -File scripts/verify/batch1/_g1_open_loop.ps1
```

运行模式与通道矩阵（fast 模式 / load 模式 / http|mq|dual 取舍）：`document/knowledge/runbook.md`。

## 五分钟看到东西（面试现场最短路径）

```powershell
# A. 后端 + 管理台（本机）
.local\run-server.bat                                   # 平台 :8400
powershell -File scripts/demo/_p0_demo.ps1              # 换电全链路 + 对账，输出可留档
cd swap-web; npm install; npm run dev                   # 管理台 http://localhost:5173 （/api 走 Vite 代理）
#    账号：admin / 密码在 .local/admin-pass.txt（gitignored，页面不内置任何凭据）

# B. 管理台自动化验收（无需人工点：零依赖 CDP + 本机 headless Chrome）
cd swap-web; npm run build                              # 先用构建产物
powershell -File scripts/verify/batch31/_c35_console.ps1   # 登录→八页走查→控制台 error 0，31/31（并出整页截图）
powershell -File scripts/verify/batch31/_c34_pages.ps1     # 页面级 HTTP 契约 + 前端类型镜像，59/59
powershell -File scripts/verify/batch31/_c38_ui_probe.ps1  # 布局/对比度探针：10 页零溢出·零裁切·零错列·全部文字与标签达 WCAG AA

# C. 云上（同源 nginx 站点；公网未放行，用隧道）
ssh -L 80:127.0.0.1:80 ubuntu@124.223.36.154            # 另开一窗保持
#    浏览器访问 http://127.0.0.1/ ：登录 → 看板 / 工单五步链 / 订单退款·冲正 / 柜详情 / 结算单
```

## 容器化（有 Docker 的机器）

```bash
# 1) 中间件（MySQL 8 + Redis 7；./db 挂进 initdb，首次启动即建库建表并跑完全部迁移）
docker compose -f docker-compose.middleware.yml up -d --wait

# 2) 平台镜像（jar 由 Maven 先构建；镜像里零密钥，全部走环境变量、缺一即启动失败）
mvn -B -ntp -DskipTests package
docker build -t swap-server:local .

# 3) 跑起来（host 网络直连上面两个中间件；首次启动 DevSeeder 播种 4 柜×12 仓 + 25 用户）
docker run -d --name swap-server --network host \
  -e SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/swap_ops?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true' \
  -e SPRING_DATASOURCE_USERNAME=root -e SPRING_DATASOURCE_PASSWORD=root \
  -e SPRING_DATA_REDIS_HOST=127.0.0.1 -e SPRING_DATA_REDIS_PORT=6379 \
  -e SWAP_DEVICE_MQ_ENABLED=false -e SWAP_DEV_ENABLED=true \
  -e SWAP_DEV_SECRET=<32hex> -e SWAP_ADMIN_TOKEN=<32hex> -e SWAP_PAY_SECRET=<32hex> \
  -e SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD=<管理端引导密码> \
  swap-server:local
docker inspect --format '{{.State.Health.Status}}' swap-server   # healthy
```

> 这条路径由 CI 的 `docker` job 真正跑一遍（`scripts/cloud/ci-container-smoke.sh`）：
> compose 起中间件 → 校验迁移建出的表数 → 构建镜像 → 容器内健康检查 UP → 容器外登录并调用 BFF 视图端点。
> 本地开发机没有 Docker，所以"compose 五分钟起环境"这句承诺的**唯一验证点就是 CI**。

## 演示与复现（P0-4）

```powershell
# 一键演示：换电全链路（TAKE/SWAP/RETURN）+ 管理端对账（break-glass）+ OpenAPI 导出
powershell -File scripts/demo/_p0_demo.ps1        # 证据：scripts/demo/_p0_demo_out.txt

# 第三方契约客户端（Python 标准库手写 HMAC，验证协议可被非 Java 端实现）
python scripts/verify/batch15/_py_contract_client.py   # 运行后建议重启 sim（更换代际）

# OpenAPI 文档页（本地）：http://127.0.0.1:8400/api/swagger-ui.html
# 离线快照：document/api/openapi.json；HTTP 请求集：scripts/demo/battery-swap-ops.http
```

## 契约（v1）

- 事件：`POST /api/device/event`（HMAC `X-Device-Sign`，canonical `cabinetNo|eventType|cellNo|batteryNo|bootId|eventSeq`）
- 心跳：`POST /api/device/heartbeat`（恒 HTTP；判活不依赖消息中间件）
- 指令：`POST /cmd`（平台→柜，同步回执；`commandSeq` 幂等）
- 协议全文：`document/plans/S0.3-设备协议v1.md`；测试向量：`swap-contract` 契约测试

## 测试与质量

```powershell
mvn -B -ntp clean test                              # 全量单测（本地全绿基线，clean 必须）
mvn -B -ntp test "-Dsurefire.runOrder=random"       # push 前随机顺序复跑（防 MP lambda 静态缓存假绿）
mvn -B -ntp clean verify                            # 覆盖率门槛 + SpotBugs（CI 同款）
cd swap-web; npm run type-check; npm run build      # 前端门禁（CI frontend job 同款）
```

- 实机剧本 54 个（`scripts/verify/README.md` 为总索引，全 PASS）；容量与 GC 证据在 `batch7/`（jtl/GC 原件归档 `diag-archive/`，不入 git）
- 前端浏览器门禁（本地，需本机 Chrome + 平台在跑）：`_c34` 契约 59/59、`_c35` 走查 31/31（含整页截图）、`_c38` 布局/对比度 10 页 hardFailures=0、`_c39` 索引审计 15/15（含无界读守类，需本机 MySQL）
- CI：`.github/workflows/ci.yml`（`build` job：verify + coverage summary；`frontend` job：npm ci + type-check + build + dist artifact；`docker` job：compose 起中间件 + 镜像构建 + 容器内业务请求冒烟）

## 文档地图

| 位置 | 内容 |
|---|---|
| `document/plans/` | S0 设计冻结 + S3 可靠性方案 + S7 运营纵深 + S8 前端与 BFF 视图层 |
| `document/block-records/` | 批次 1-27、29-30 实施记录（做了什么/取舍/验证证据；28 为 Agent 智能化设计，未开工） |
| `document/pitfalls/` `fixes/` | 踩坑与修复（环境/编码/并发/JVM） |
| `document/knowledge/` | 领域知识（含 architecture / runbook / s5-quality-delivery） |
| `scripts/verify/` | 剧本与证据（README 为总索引） |
