# 运行手册（battery-swap-ops 本地全栈）

> 适用：Windows 本机开发/联调/压测。云端部署见 §9。
> **Git 提交 / CI 门禁 / 云上部署的可执行命令序列**：见 `document/Git与CI与云部署-操作手册.md`（本文件只讲本地运行）。
> 所有启动器在 `.local/`（gitignored），密钥文件同目录，**仓库零明文**。

## 1. 中间件（先启）

| 中间件 | 启动 | 健康检查 |
|---|---|---|
| MySQL 8 | Windows 服务（root/root，库 `swap_ops`） | `mysql -uroot -proot -e "select 1"` |
| Redis | `redis-server F:\Redis\redis.windows.conf --dir F:\Redis`（**--dir 必须**：默认 CWD 下 RDB 不可写会启动失败） | `redis-cli ping` |
| RocketMQ | namesrv 9876 → broker 10911 → **proxy 8081**（store 在 `F:\RocketMQ\store`；启动方式/topic 清单/40014 坑见 `pitfalls/mq-store-rebuild-topic-recovery.md`） | `netstat -ano | findstr "9876 10911 8081"` |

> 坑：只起 broker 不起 proxy（8081），MQ 收发静默失败——先查 proxy。
> 坑：store 重建/切换后必须重建业务 topic（`swap-device-event` / `swap-alarm`，`+message.type=NORMAL`），否则 40402。

## 2. 启动器矩阵（`.local/`）

| 脚本 | 用途 | 关键参数（相对默认） |
|---|---|---|
| `run-server.bat` | 常规平台 | dev 联调端点、种子数据 |
| `run-server-fast.bat` | 剧本/快节奏 | 心跳超时 5s、离线扫描 2s、订单扫把 2s、对账 2s、延迟轮询 500ms、SLA 高危 0、电池扫描 3s、暴露 actuator shutdown |
| `run-server-load.bat` | 压测/容量 | 512m 定堆 + `-Xlog:gc`（`gc.log`）、**限流关闭**（容量测量语义） |
| `run-server-2nd.bat` | 双实例（看护/锁验证） | 端口 8401 |
| `run-sim.bat` | 常规模拟器 | event-channel 默认 http |
| `run-sim-dual.bat` | 双通道模拟器 | `event-channel=dual`、心跳 2s、`charge-speed-factor=900`、charge-tick 500ms |
| `start-broker.bat` / `start-broker-proxy.bat` | MQ 起停辅助 | — |

日志：`server.out.log`/`server.err.log`（被 logback 持有，读取需 `FileShare.ReadWrite` 共享打开）；`gc.log` 为 load 模式 GC 画像。

## 3. 构建与测试

```powershell
mvn -B -ntp clean test                              # 全量单测基线（clean 必须）
mvn -B -ntp test "-Dsurefire.runOrder=random"       # push 前随机顺序复跑（防静态缓存假绿）
mvn -B -ntp clean verify                            # CI 同款：含 *IT（需 Docker）+ 覆盖率门槛 + SpotBugs
mvn -B -ntp clean verify -DskipITs                 # Windows 本机无 Docker：跳 IT，其余与 CI 同源
mvn -B -ntp package                                 # 出双端 jar（启动器与云部署依赖）
cd swap-web; npm run type-check; npm run build       # 前端门禁（vue-tsc + vite build）
```

> 单模块跑测试必须带 `-am`（本地仓无同仓 `swap-contract:1.0.0` 产物）；
> 配 `-Dsurefire.failIfNoSpecifiedTests=false` 才能用 `-Dtest=XxxTest` 跑单类。

## 4. 健康检查

- 平台：`http://localhost:8400/api/actuator/health`（fast/load 模式暴露 shutdown/circuitbreakers/bulkheads）
- 模拟器：`http://localhost:8500/actuator/health`；联调端点（dev 模式）：`/api/dev/...`
- 停止：fast 模式 `POST /api/actuator/shutdown`（优雅停机验证用）；常规 `taskkill /F /IM javaw.exe`（按窗口名或 pid，`.local/*.pid`）

## 4.5 管理端登录（S7 WP-A）

- 登录：`POST /api/admin/auth/login` `{username,password}` → `data.token`；请求头 `X-Admin-Token` 携带会话 token；
- 角色：SUPER/OPS/FINANCE/SUPPORT（矩阵见 `rbac-and-audit.md`）；引导管理员由 dev 种子
  （`SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD` env，本地 `.local/admin-pass.txt`）；
- **脚本/运维沿用静态 token**（`SWAP_ADMIN_TOKEN`，break-glass=SUPER，审计 username=bootstrap，不随登出吊销）；
- 审计查询：`GET /api/admin/account/op-log`（需 `admin:admin:manage`，即 SUPER 或 bootstrap）。

## 5. 通道矩阵（http / mq / dual）

| 通道 | 事件路径 | 适用 | 取舍 |
|---|---|---|---|
| `http` | sim → HTTP → 平台（HMAC） | 开发/联调/单点故障定位 | 平台宕机期间事件重试缓冲在 sim（有界队列+退避） |
| `mq` | sim → RocketMQ → 平台消费（保序） | 生产推荐 | 依赖 MQ 可用性；心跳恒 HTTP |
| `dual` | HTTP+MQ 双发 | 演练/灰度 | 平台按 `(bootId,eventSeq)` 幂等去重，**双通道不产生双事件** |

- sim 侧：`--swap.sim.event-channel=http|mq|dual`（默认 http）。
- 平台侧：MQ 消费 `swap.mq.consumer-group=platform-device-event` 常开；HTTP 事件入口恒开（兜底）。
- **生产口径：mq（或 dual 过渡）；http 仅降级形态**——HttpEventReporter 重试耗尽即丢弃（降级语义，详见 reporter 注释）。

## 6. 模式参数速查（快节奏 = 剧本用）

| 参数 | 常规 | fast |
|---|---|---|
| `swap.device.heartbeat-timeout-seconds` | 60 | 5 |
| `swap.alarm.offline-scan-interval-ms` | 30s | 2s |
| `swap.order.sweep-interval-ms` | 30s | 2s |
| `swap.device.reconcile-interval-ms` | 15s | 2s |
| `swap.delay.poll-interval-ms` | 5s | 500ms |
| `swap.workorder.sla-minutes-high` | 60 | 0 |
| `swap.battery.scan-interval-ms` | 1h | 3s |

load 模式附加：`-Xms512m -Xmx512m -Xlog:gc:file=gc.log:time,uptime` + `swap.ratelimit.enabled=false`（说明注释在 bat 内）。

## 7. 排障索引

| 症状 | 排查 → 档案 |
|---|---|
| MQ 消息不达 | proxy 8081 未起 → §1 |
| Redis 启动失败 | RDB 目录不可写 → 加 `--dir F:\Redis` |
| 平台启动即退 | 缺 SWAP_DEV_SECRET/SWAP_ADMIN_TOKEN/SWAP_PAY_SECRET → §2 |
| 中文乱码/脚本报错 | PS 编码坑 → `pitfalls/ps-utf8-bom-and-mojibake.md`、`pitfalls/gbk-source-encoding.md` |
| 端口占用 | 8400/8500/8401/9876/10911/8081 → `netstat -ano` + `taskkill /F /PID` |
| 幂等/重复事件 | 序守卫双线 → `plans/S0.3` §幂等 |
| 单测假绿/真红 | 静态缓存坑 → `pitfalls/mp-lambda-cache-test-order.md`（push 前 random 复跑） |
| 对账告警 | 14 组不变量语义 → `knowledge/agent-seam.md` 等 + `fixes/` 各档 |
| Redis 故障/数据丢失后 | 恢复清单（rebuild-alloc→对账→workerId 日志）→ `pitfalls/redis-data-loss-runtime-consistency.md`；级联拖死全站 → `pitfalls/redis-fault-cascade-no-timeout.md` |
| GC/压测分析 | `gc.log` + `scripts/verify/batch7/_load_out.txt` |

### 7.1 监控接入（P1-6）

- **Prometheus 指标**：`GET /api/actuator/prometheus`——**需 `X-Admin-Token`**（health/info 公开；无 token 401）。
  业务指标 6 项：`swap_outbox_backlog` / `swap_outbox_dead` / `swap_alarm_unhandled` /
  `swap_delay_backlog{topic}` / `swap_reconcile_violations` / `swap_alloc_available`；JVM/Hikari/HTTP 为 Micrometer 自带。
  求值异常统一降级 -1（不拖垮抓取链路）。
- **面板**：`scripts/verify/batch20/grafana-dashboard.json` 导入任意 Grafana（数据源变量 DS_PROMETHEUS）。
  抓取配置示例：`job_name: swap-ops`、`metrics_path: /api/actuator/prometheus`、
  `headers: {X-Admin-Token: <经 secrets 注入，零明文>}`、targets `127.0.0.1:8400`。
- **日志 traceId**：`logging.pattern.console` 已含 `%X{traceId:-}`；HTTP 请求沿用/生成 `X-Trace-Id` 头。
- 证据：`scripts/verify/batch20/_c24_out.txt`（9/9 PASS）。

### 7.2 告警出站 webhook 接入（P1-11）

- **配置**：`swap.alarm.webhook.url` / `secret`（env `SWAP_ALARM_WEBHOOK_URL` / `SWAP_ALARM_WEBHOOK_SECRET`）。
  本地联调：`.local/webhook-url.txt` + `.local/webhook-secret.txt`（gitignored，run-server*.bat 已有读取行）；**url 为空=整体禁用**。
- **事件契约**：POST JSON（告警信封，与 swap-alarm MQ 同构：alarmId/alarmType/deviceType/deviceNo/
  content/handled/createTime/handledTime/**eventKind**/traceId）；头 `X-Swap-Event: RAISED|HANDLED|RECOVERED`、
  `X-Swap-Sign: hex(HMAC-SHA256(secret, raw body))`——接收端验签必须用**原始字节**。
- **投递语义**：尽力而为——单发送线程 + 有界队列 1000（满丢弃计数）；单事件内联重试 3 次（退避 500ms×n）；
  连续失败 5 次熔断 60s。失败只影响 webhook 自身（告警入库/MQ 出站不受影响，已在 `_c26` 隔离轮实证）。
- **排障关键词**：服务日志 grep `webhook`——"已投递/发送异常/非 2xx/熔断打开/队列已满"五类；
  本地验证接收端：`python scripts/verify/batch21/_c26_mock_receiver.py 8490 <events.jsonl> <secret>`（记录含 sign_ok）。
- 证据：`scripts/verify/batch21/_c26_out.txt`（12/12 PASS；RAISED 22s / RECOVERED 4s / 隔离轮 26s 入库）。

## 8. 实机剧本

总索引 `scripts/verify/README.md`（**52 个剧本与验证脚本** + 容量证据；`_out.txt` 为统计数据，原件归档 diag-archive/）。
剧本前置 = 中间件 + fast 模式平台 + dual 模拟器（`_g*` 部分只需 http）。

管理台（S8）另有两层页面级验收：`batch31/_c34_pages.ps1`（HTTP 契约 + 前端类型镜像）与
`batch31/_c35_console.ps1`（用构建产物 `dist/` 起 `vite preview`，再用零依赖 CDP 驱动本机 headless Chrome
真实登录并逐页走查）；后者需先 `cd swap-web; npm run build`。

## 9. 云端部署（2026-09-14 首次部署；2026-09-19 增补管理台与 nginx）

- 形态：`jar + systemd`（`swap-server` :8400 / `swap-sim` :8500，堆 768m/256m），
  目录 `/opt/swap`，配置 `/opt/swap/config/swap.env`（600，服务器本地生成密钥）；部署脚本 `scripts/cloud/`。
- 通道：**HTTP 事件通道**（`SWAP_DEVICE_MQ_ENABLED=false`）——未装 RocketMQ；上 MQ 通道见 §5 矩阵（需先评估内存）。
- 网络：ufw 仅 22；8400/8500 仅本机。远程访问走 SSH 隧道：
  `ssh -L 8400:127.0.0.1:8400 ubuntu@124.223.36.154` → `http://127.0.0.1:8400/api`。
- 运维：`sudo systemctl restart swap-server swap-sim`；`journalctl -u swap-server -f`；
  健康 `curl localhost:8400/api/actuator/health`；Redis 故障恢复用 `POST /admin/ops/rebuild-alloc`（§2.1 预案）。
- 备份：每日 02:30 cron（`/opt/swap/config/backup.sh`，MySQL dump+Redis RDB，14 天保留，恢复演练已做）；
  模板在 `scripts/cloud/backup.sh`。
- 详细部署记录（含冒烟证据与待办）见工作区根《服务器连接文档.md》§九。
