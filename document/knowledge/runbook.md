# 运行手册（battery-swap-ops 本地全栈）

> 适用：Windows 本机开发/联调/压测。云端部署待口径（见 §9）。
> 所有启动器在 `.local/`（gitignored），密钥文件同目录，**仓库零明文**。

## 1. 中间件（先启）

| 中间件 | 启动 | 健康检查 |
|---|---|---|
| MySQL 8 | Windows 服务（root/root，库 `swap_ops`） | `mysql -uroot -proot -e "select 1"` |
| Redis | `redis-server F:\Redis\redis.windows.conf --dir F:\Redis`（**--dir 必须**：默认 CWD 下 RDB 不可写会启动失败） | `redis-cli ping` |
| RocketMQ | namesrv 9876 → broker 10911 → **proxy 8081**（`.local/start-broker.bat` + `start-broker-proxy.bat`） | `netstat -ano | findstr "9876 10911 8081"` |

> 坑：只起 broker 不起 proxy（8081），MQ 收发静默失败——先查 proxy。

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
mvn -B -ntp test "-Dsurefire.runOrder=random"       # push 前随机顺序复跑
mvn -B -ntp clean verify                            # 覆盖率门槛（CI 同款：server 65/sim 55/contract 70）
mvn -B -ntp package                                 # 出双端 jar（启动器依赖）
```

## 4. 健康检查

- 平台：`http://localhost:8400/api/actuator/health`（fast/load 模式暴露 shutdown/circuitbreakers/bulkheads）
- 模拟器：`http://localhost:8500/actuator/health`；联调端点（dev 模式）：`/api/dev/...`
- 停止：fast 模式 `POST /api/actuator/shutdown`（优雅停机验证用）；常规 `taskkill /F /IM javaw.exe`（按窗口名或 pid，`.local/*.pid`）

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
| 对账告警 | 9 组不变量语义 → `knowledge/agent-seam.md` 等 + `fixes/` 各档 |
| GC/压测分析 | `gc.log` + `scripts/verify/batch7/_load_out.txt` |

## 8. 实机剧本

总索引 `scripts/verify/README.md`（19 个剧本 + 容量证据；`_out.txt` 为统计数据，原件归档 diag-archive/）。
剧本前置 = 中间件 + fast 模式平台 + dual 模拟器（`_g*` 部分只需 http）。

## 9. 云端部署（占位）

口径待拍板：部署形态（jar+systemd / Docker）、端口与 HTTPS、密钥注入方式（环境变量/secrets）、
监控接入（actuator + GC 日志）。落地后本节替换为生产运行手册；未落地前以本地全栈为准。
