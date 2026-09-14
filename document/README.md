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

### knowledge（节选：入口性文档）
- knowledge/architecture.md（架构全景/组件机制/安全资金边界/部署形态）
- knowledge/runbook.md（中间件/启动器矩阵/通道矩阵/排障索引/云端占位）
- knowledge/ops-troubleshooting.md（慢 SQL 治理 + 8 类边界极限故障预案，S5 运维审查）
- knowledge/s5-quality-delivery.md（S5 质量线：压测/GC/覆盖率设计与结论）
- 其余为专题档（outbox/支付仲裁/熔断/缓存/策略/调拨/Agent 接缝等），见目录。

### 剧本证据
- `scripts/verify/README.md`（19 剧本总索引 + 前置 + 命名约定；`_out.txt` 为统计证据，原件归档 diag-archive/）

### pitfalls
- pitfalls/gbk-source-encoding.md
- pitfalls/ps-utf8-bom-and-mojibake.md

### 外部关联（工作区根，不入本仓库）
- `项目一-换电运营平台-设计备忘.md`（项目定位/架构/分期）
- `项目一-阶段计划.md`（S0-S5 阶段与小阶段门禁）
- `项目一-参考基准.md`（成熟项目/协议/标准参照与取舍）
