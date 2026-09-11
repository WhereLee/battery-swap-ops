# battery-swap-ops

两轮电动车换电柜运营平台（样例工程）：**从"设备可靠接入"到"设备经营"**——单城市多站点换电网络，
覆盖设备接入、换电订单闭环、可靠性工程与运营调度。

> 设计文档：`document/plans/`（S0 设计冻结全集：领域模型 / 状态机 / 设备协议 / API / 表约束 / 剧本）
> 阶段计划见工作区 `项目一-阶段计划.md`；参考基准（成熟项目/协议/标准）见工作区 `项目一-参考基准.md`。

## 模块

| 模块 | 说明 | 端口 |
|---|---|---|
| `swap-contract` | 双端契约：枚举 / HMAC canonical / 契约向量测试 | — |
| `swap-server` | 换电运营平台（设备接入 + 业务，context-path `/api`） | 8400 |
| `swap-sim` | 换电柜模拟器（N 柜 × M 仓，心跳/事件/故障注入） | 8500 |

技术栈：Java 17 / Spring Boot 3.5.16 / MyBatis-Plus 3.5.7 / MySQL 8 / Redis / RocketMQ（S3 接入）。

## 快速开始（本地）

```bash
# 1) 建库建表
mysql -uroot -proot < db/00-create-database.sql
mysql -uroot -proot < db/01-swap-schema.sql

# 2) 设备密钥（32hex，仅环境变量注入，仓库零明文）
#    PowerShell: $env:SWAP_DEV_SECRET = "<32位小写hex>"

# 3) 启动平台（联调端点 + 种子数据开关）
mvn -pl swap-server spring-boot:run -Dspring-boot.run.arguments="--swap.dev.enabled=true"

# 4) 启动模拟器（联调端点开关）
mvn -pl swap-sim spring-boot:run -Dspring-boot.run.arguments="--swap.sim.dev-enabled=true"

# 5) 剧本 1（开仓闭环）：平台 8400 + 模拟器 8500 就绪后
powershell -File scripts/verify/batch1/_g1_open_loop.ps1
```

## 契约（v1）

- 事件：`POST /api/device/event`（HMAC `X-Device-Sign`，canonical `cabinetNo|eventType|cellNo|batteryNo|bootId|eventSeq`）
- 心跳：`POST /api/device/heartbeat`（恒 HTTP；判活不依赖消息中间件）
- 指令：`POST /cmd`（平台→柜，同步回执；`commandSeq` 幂等）
- 幂等：`(bootId,eventSeq)` 序守卫 + `commandSeq` 双线（已受理/已被拒）

## 阶段状态

- [x] S0 设计冻结（六份设计文档 + 自查记录）
- [ ] S1 骨架 + 双端 + 指令-事件闭环（进行中）
- [ ] S2 换电闭环（下单/分配/取还/计费）
- [ ] S3 可靠性深水（MQ/对账/延迟任务/支付幂等）
- [ ] S4 运营与调度（可砍）
- [ ] S5 质量与交付（压测/GC/部署）
