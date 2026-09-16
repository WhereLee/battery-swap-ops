# 坑位：RocketMQ store 重建后 topic 丢失 + proxy 消息类型校验 40014（已处置·dev 口径）

> 状态：**已处置**（批次18，2026-09-16）。触发背景：C 盘水位 91% → broker 拒写
> （50001 "service not available, disk full"）→ outbox 大面积 OUTBOX_DEAD。

## 处置主线（为什么重建 store）

- 根因：broker store 默认在 `C:\Users\lrs\store`（user.home），C 盘 198/220GB。
- 处置：`broker.conf` 增加 `storePathRootDir = F:/RocketMQ/store` → 停 broker/proxy → 删除旧 store
  （回收 2.1GB，C 盘 90%→89.1%）→ 重启三进程（namesrv 9876 / broker 10911 / proxy 8081）。

## store 重建引出的三连坑

1. **topic 配置随 store 丢失**（topics.json 在 store/config/ 内）。所有业务 topic 消失，投递报
   `40402 No topic route info`。恢复：用 mqadmin 重建**全部**业务 topic：
   ```
   mqadmin updateTopic -n 127.0.0.1:9876 -c DefaultCluster -t <topic> -a "+message.type=NORMAL"
   ```
   属性语法 `+key=value`（逗号分隔多个）；**当前业务 topic 清单（改代码新增 topic 时须同步维护）**：
   - `swap-device-event`（设备事件；sim 生产 / 平台消费 `platform-device-event`）
   - `swap-alarm`（告警；`AlarmEventPublisher`/`AlarmOutboxPublisher` 硬编码，易漏！）
2. **gRPC 发送 40014 消息类型校验**：`TopicMessageType validate failed, the expected type is
   UNSPECIFIED, but actual type is NORMAL`（response-code 40014，`ProxyException`）。
   - 已查明的链路（反编译 5.3.1 jar）：`ProducerProcessor` →（`ProxyConfig.isEnableTopicMessageTypeCheck()`
     为 true 时）→ `MetadataService.getTopicMessageType()` → `ClusterMetadataService` 的
     `topicConfigCache`（Guava LoadingCache）→ 空/`EMPTY_TOPIC_CONFIG` 即返回 **UNSPECIFIED** →
     `DefaultTopicMessageTypeValidator.validate` 严格比对后拒绝。
   - **对照事实（诚实记录）**：broker 侧数据正常——`topics.json` 与 `mqadmin topicStatus` 均显示
     `message.type=NORMAL`；同 topic 用 remoting（`mqadmin sendMessage`）发送 **SEND_OK**；
     重建前（09-16 11:49 前）gRPC 投递长期正常（SENT 385 条）。**该 proxy 通道为何判 UNSPECIFIED
     未彻底定位**（涉 5.3.1 staticopic 路径），如需生产级结论应单独立项。
   - **dev 处置**：`proxy-dev.json` 增加 `"enableTopicMessageTypeCheck": false`（官方 4.x 兼容开关；
     实测 5.3.1 未配置时校验默认开启）→ 重启 proxy → 40014 消失、投递恢复。
     注意：这是 dev 放宽口径；生产应保持强校验并确保 topic 属性与消息类型一致。
3. **验证方式（以后照做）**：
   - 探针：`mqadmin sendMessage -n 127.0.0.1:9876 -t <topic> -p probe` → SEND_OK；
   - 投递：outbox `status=NEW` 消化至 0、`SENT` 持续增长；`mqadmin topicStatus` 的 Max Offset > 0。

## 环境要点（本地 dev）

- store：`F:\RocketMQ\store`（broker.conf）；旧 `C:\Users\lrs\store` 已清理。
- 三进程启动：`start-broker.bat`（broker）；**proxy 必须经 bat 启动**（`.local/_start-proxy.bat` 式：
  `start /min cmd /c "cd /d %ROCKETMQ_HOME%\bin && mqproxy.cmd -pm cluster -n 127.0.0.1:9876 -pc ..."`
  ——用 `Start-Process` 直起 mqproxy.cmd 会静默失败）。
- 重启顺序：broker →（等 10911 LISTENING）→ proxy（等 8081 LISTENING）。
- 切换 store 位置/重建后，**必须重建 topic 清单**（见上），否则 40402。

**记录**：批次18 §4（`block-records/批次18-dev复位收敛与台账孤占归零.md`）；涉及脚本
`.local/_start-proxy.bat`（临时运维件，gitignored）。
