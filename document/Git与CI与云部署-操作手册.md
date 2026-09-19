# Git 与 CI 与云部署 · 操作手册（battery-swap-ops）

> 定位：**照着敲能做完**的操作手册。三件事各一节，末尾把它们串成一次块循环。
> 与既有文档的分工：本地运行/端口/剧本前置看 `knowledge/runbook.md`；本仓历史与云上部署记录看
> 工作区根《服务器连接文档.md》§九 与 `block-records/`。本手册只写"怎么做"，不重复"为什么这样设计"。
> 事实来源：2026-09-19 实测（`git remote -v`/`git branch -vv`/`.git/hooks` 清点/CI run `35427406950`
> /`scripts/cloud/*` 逐档/SSH 只读探测），非沿用旧叙述。

---

## 0. 六条最常用（先给命令，细节在后）

```powershell
# 全量本地门禁（CI 同款，Windows 无 Docker 时跳 IT）
mvn -B -ntp clean verify -DskipITs
# 前端门禁
cd swap-web; npm run type-check; npm run build
# 提交（中文 message 必须走文件，见 §1.2）
git add -A; git commit -F ".local\_commit_msg.txt"; git push origin master
# 看 CI 真实结论（不要用管道接 gh run watch，见 §2.4）
gh run list --branch master --limit 3 --json databaseId,status,conclusion,headSha
# 连服务器
ssh ubuntu@124.223.36.154
# 平台健康（注意 context-path=/api，别用 /api/health）
curl http://127.0.0.1:8400/api/actuator/health
```

---

## 1. Git 提交方式

### 1.1 仓库实况

| 项 | 值 |
|---|---|
| remote `origin` | `git@github.com:WhereLee/battery-swap-ops.git`（SSH，非 HTTPS） |
| 分支 | 单一 `master`，跟踪 `origin/master`；无 feature 分支流程 |
| 自定义 hooks | **无**（`.git/hooks` 仅 14 个 `.sample`）→ 本地没有任何自动校验，**门禁全在 CI**，所以"push 前本地全绿"是纪律不是工具 |
| 旧仓库 | `WhereLee/time.git`（= `inteink-faster`）**只读**，不再推新改动，仅作编码范例参照 |

### 1.2 提交规范

- 前缀沿用：`feat:` / `fix:` / `test:` / `docs:` / `ci:` / `refactor:`；带阶段时写括号，如 `feat(S8):`、`docs(S8):`。
- 标题一句话讲"做了什么"，正文分行讲**取舍、验证数字、自我纠正**（近期提交都是这个形态，例：`a880ae5`）。
- **中文 message 必须写成文件再 `-F` 提交**：PowerShell 5.1 下 `-m "中文…"` 会因引号/编码被拆分或乱码。
  放 `.local/_commit_msg*.txt`（该目录 gitignored，不会误提交）。

### 1.3 标准动作序列

```powershell
cd c:\Users\lrs\Desktop\py\interview\battery-swap-ops
mvn -B -ntp clean verify -DskipITs          # 必须 BUILD SUCCESS；clean 是硬要求
cd swap-web; npm run build; cd ..           # 前端有改动时
git status --short                          # 先眼看一遍：有没有意外文件（尤其 .local/、node_modules/、dist/）
git add --dry-run <新目录>                   # 新增目录必查，确认 gitignore 生效（批次30 用它核实 swap-web 只入 24 文件）
git add -A
git commit -F ".local\_commit_msg.txt"
git push origin master
```

**代码与测试同批推送**；纯文档可攒批（避免中间态 CI 噪音）。

### 1.4 push 的"假失败"（每次都别被骗）

`git push` 经 PowerShell 管道时常报：

```
fatal: unknown write failure on standard output
```

这是**回显写入失败，不是推送失败**。以实际引用状态为准：

```powershell
git log --oneline -1; git status -sb | Select-Object -First 1   # 显示 master...origin/master 即已同步
```

本手册编写过程中两次推送都出现该报错，两次实际都成功（`a57a73a..ad5fd06`、`746a55d..a880ae5`）。

### 1.5 绝对不能进仓

`.local/`（一切密钥/口令/token/pass 文件）、`node_modules/`、`swap-web/dist/`、
`swap-web/src/api/schema.d.ts`（`gen:api` 产物）、`*.log`、大体积压测原件（走工作区根 `diag-archive/`，不入 git）。
**密钥零明文**适用于代码、文档、提交信息、聊天、记忆五处。

---

## 2. CI 方式

### 2.1 workflow 实况（`.github/workflows/ci.yml`）

触发：`push` 与 `pull_request` 到 `master`。**两个 job 并行**：

| job | 步骤 | 实测耗时 |
|---|---|---|
| `build` | checkout@v7 → setup-java@v5 (temurin 17, cache maven) → `mvn -B -ntp verify` → Coverage summary（`if: always()`，awk 解析四模块 `jacoco.csv`） | 155 s |
| `frontend` | checkout@v7 → setup-node@v4 (node 22, cache npm 按 `swap-web/package-lock.json`) → `npm ci` → `npm run type-check` → `npm run build` → upload `dist`（保留 7 天） | 27 s |

### 2.2 门禁清单（任一红即不可继续）

1. **单测全绿**（四模块，当前 480）+ **IT 全绿**（CI 有 Docker，failsafe 真跑 `*IT`）。
2. **JaCoCo 覆盖率门槛**（绑 `verify` 的 `check`）：server **65%** / contract 70% / sim 55% / agent 65%，
   由 `pom.xml` 属性 `jacoco.line.minimum` 控制，注释写明"基线锁定，**只升不降**"。
3. **SpotBugs**（绑 `verify`，`threshold=High`、`effort=Max`、只扫主代码）——高优先级问题即红。
4. **契约版本一致性**：双端共用 `swap-contract` 枚举/HMAC，向量测试在同 job 跑。
5. **前端 `vue-tsc --noEmit`**：类型错误即红（`noUnusedLocals`/`noUnusedParameters` 会抓死变量）。
6. **前后端权限码一致性**：`PermissionCodeContractTest` 读 `swap-web/src/api/permissions.ts`，
   断言所用码 ⊆ `AdminRole.ALL_CODES`——**在 `build` job 里**，所以漂移是 build 红而不是 frontend 红。
   （`swap-web` 未落地时它按 `Assumptions` 跳过；批次30 起转为实跑，全量 Skipped 由 1 → 0。）

### 2.3 本地等价命令与差异

```powershell
mvn -B -ntp clean verify            # 有 Docker 时与 CI 完全等价（含 IT）
mvn -B -ntp clean verify -DskipITs # Windows 本机无 Docker：跳过 IT，其余同源
mvn -B -ntp clean test              # 快速：只单测，不查覆盖率门槛与 SpotBugs
```

- **`clean` 不可省**：增量编译有"跑旧类"误判史。
- 单模块构建必须带 `-am`（本地仓库无同仓 `swap-contract:1.0.0` 产物），
  配 `-Dsurefire.failIfNoSpecifiedTests=false` 才好用 `-Dtest=Xxx` 跑单类。
- **本地无 Docker 是设计内约束**：`mvn test` 阶段不触发容器（IT 走 failsafe 的 `integration-test`），
  所以本地全绿基线不会被容器破坏。

### 2.4 看 CI：用轮询结论，不要用 `gh run watch` 接管道

```powershell
$runs = gh run list --branch master --limit 1 --json databaseId,status,headSha | ConvertFrom-Json
$id = $runs[0].databaseId
for ($i=1; $i -le 20; $i++) {
  $r = gh run view $id --json status,conclusion | ConvertFrom-Json
  if ($r.status -eq "completed") { "COMPLETED: $($r.conclusion)"; break }
  Start-Sleep -Seconds 25
}
```

两条教训：
- `gh run watch` 是长任务，历史上被后续后台命令复用同一 terminal 而**中断**，导致漏看最终结果；
  且它接管道会阻塞（同 `pitfalls/ps-pipe-blocks-on-spawned-jvm.md`）。
- **`--jq` 在 PowerShell 下会被 `\(.name)` 转义搞坏**（报"无法将 .name 识别为 cmdlet"）。
  改用 `--json <字段> | ConvertFrom-Json` 再遍历。

### 2.5 失败定位

```powershell
gh run view <id> --json jobs | ConvertFrom-Json          # 哪个 job、哪一步
gh run view --job <jobId> --log-failed                    # 失败日志
git show --stat HEAD                                      # 对回本次提交内容
```

**门禁本身有效性已被实证过**（批次23）：故意让 IT-3 失败的探针 commit `6218699`
→ run `35114318706` **failure** → revert `972e1f3` → run `35114891453` success。
不是"配了但挡不住"的装饰门禁。

---

## 3. 服务器连接与云上操作

### 3.1 连接

| 项 | 值 |
|---|---|
| 主机 | `124.223.36.154`（腾讯云轻量，4 核 / 标称 4G→实 3.6Gi / **3Mbps** / 系统盘 40G 已用 ~12G） |
| 用户 | `ubuntu`（**sudo 免密**） |
| 认证 | 本机 SSH 密钥免密；服务器**已禁用密码登录** |
| 端口 | 仅 22 开放（ufw active，只放行 22 v4+v6） |
| 传文件 | `scp 本地文件 ubuntu@124.223.36.154:/tmp/`（**scp 永远在本机执行，绝不嵌进 ssh 引号里**） |
| 远程访问应用 | `ssh -L 8400:127.0.0.1:8400 ubuntu@124.223.36.154` → `http://127.0.0.1:8400/api` |

**操作铁律**（工作区根《服务器连接文档.md》§二，全部沿袭）：
复杂/嵌套引号一律"写脚本 → scp → ssh 执行"；临时脚本用完即删（远端 `/tmp` 与本地都要清）；
密钥不进 git；实质操作（创建/改/删/停启/DDL）先向用户确认；**部署后必须校验**（提交号、特征串、active 状态），
不能只看"命令没报错"；无法验证的事实明说"无法判断"。

### 3.2 云上布局

```
/opt/swap/                      swap-server-1.0.0.jar  swap-sim-1.0.0.jar  logs/  backup/{mysql,redis}
/opt/swap/config/swap.env       600，密钥全在服务器本地生成（首次部署时 openssl rand）
/opt/swap/config/backup.sh      700 + backup.env 600，root crontab 每日 02:30
/etc/systemd/system/swap-{server,sim}.service   模板在 scripts/cloud/
```

`swap.env` 的键（值一律不外泄）：
`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`、`SPRING_DATA_REDIS_PASSWORD`、
`SWAP_DEV_SECRET`（**必须恰 32 hex**，长度不对两端校验会拒）、`SWAP_ADMIN_TOKEN`、`SWAP_PAY_SECRET`、
`SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD`（引导 admin 登录口令，冒烟脚本从这取）、
`SWAP_DEVICE_MQ_ENABLED=false`、`SWAP_SIM_MQ_ENABLED=false`、`SWAP_SIM_BASE_URL`。

服务：`swap-server` :8400（`-Xms256m -Xmx768m`）、`swap-sim` :8500（`-Xms128m -Xmx256m`），均 `Restart=on-failure`。
DB：MySQL 库 `swap_ops`，应用账号 `swap_app@localhost`；备份账号 `swap_backup@localhost`（最小权限）。
通道：**HTTP**（未装 RocketMQ）。

### 3.3 三种动作

**A. 首次全量部署**（幂等；`swap.env` 已存在则跳过密钥/建库/迁移段）

```powershell
mvn -B -ntp package            # 出双端 jar
# 前置：.local/cloud-redis-pass.txt 必须存在（gitignored），否则脚本自己 FATAL 退出
powershell -File scripts\cloud\deploy.ps1
```

它做的事：staging 到 `%TEMP%` → scp `/tmp/swapdeploy` → 远端 `deploy-remote.sh`
（建目录/拷 jar → 首次生成 env+建库建账号 → 按序灌 `db/*.sql` → dev 一次性种子 10 柜后 kill 进程 →
装 systemd → `is-active` + 两端 health 校验）→ 清理本地与远端 staging。
⚠️ **jar 合计约 126 MB 走 3Mbps 上行，要几分钟**，别以为卡住了。

**B. 增量同步（日常每批上云用这个，模板 = `scripts/cloud/rollout-wpd.sh`）**

顺序固定为 **快照 → 迁移 → 换 jar → 重启 → 冒烟**：

```bash
# 1) 先备份，永远在 DDL 之前
sudo /opt/swap/config/backup.sh
# 2) 应用待补迁移（当前缺 db/16、db/17 —— DDL 必须先经用户确认）
mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops < /tmp/swapdeploy/16-data-scope.sql
mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops < /tmp/swapdeploy/17-work-order-scope.sql
# 3) 停 → 换 → 启（chown 别忘，否则 ubuntu 用户下次改不动）
sudo systemctl stop swap-server
sudo cp /tmp/swapdeploy/swap-server-1.0.0.jar /opt/swap/ && sudo chown ubuntu:ubuntu /opt/swap/swap-server-1.0.0.jar
sudo systemctl start swap-server; sleep 15
# 4) 校验（不是"没报错"就算完）
systemctl is-active swap-server; curl -sf http://127.0.0.1:8400/api/actuator/health
```

迁移脚本本身**幂等**（`information_schema` 判存 + 存储过程包裹），重复执行安全。

**C. 只读探测**（判断"云上到底部署到哪"，交接时必做）

用 `information_schema` + `unzip -l` jar 类指纹 + `journalctl` 三件套，**不要信文档叙述**。
可复用本次做法：脚本写本地 `.local/probe-cloud-*.sh`（全英文）→ scp → `ssh ... "bash /tmp/x.sh; rm -f /tmp/x.sh"`。

一次判定实例（2026-09-19 结论）：jar 类时间戳 2026-09-15 15:55，
`DataFilterAspect`/`AdminViewController`/`ActionsSupport`/`MybatisPlusConfig`/Micrometer 指标/webhook **均为 0**，
而 `DevResetService`/`WorkerIdRegistry` = 1 → 代码停在批次19 之后，**落后 11 个批次**。

> 踩过的坑：第一次探测把表名写成 `arrears`（实际 `arrears_record`），得到"db/14 未应用"的**假阴性**，
> 与文档记录冲突才回源核对 SQL 发现是自己错。**探测脚本的表名/列名必须回源到 `db/*.sql` 核实**。

### 3.4 冒烟模板

`scripts/cloud/smoke-cloud.sh` 为通用冒烟；`rollout-wpd.sh` 里有**完整可用的登录与工单五步链 curl**，
照抄即可（参数形态已核实与后端一致：动作参数走 query，不放 body）：

```bash
ADMIN_PASS=$(grep '^SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD=' /opt/swap/config/swap.env | cut -d= -f2)
ATOKEN=$(curl -sf -X POST $B/admin/auth/login -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
for STEP in "triage?severity=MEDIUM&remark=smoke" "assign?handlerId=1&remark=smoke" \
            "start?remark=smoke" "verify?remark=smoke" "close?remark=smoke"; do
  curl -sf -X POST "$B/admin/work-order/$WOID/${STEP%%\?*}?${STEP#*\?}" -H "X-Admin-Token: $ATOKEN"
done
```

管理端头是 `X-Admin-Token`（**不是** `Authorization: Bearer`，用错会得到 401）；用户端是 `X-User-Token`。

### 3.5 备份与恢复

- 每日 02:30 root cron：`backup.sh`（mysqldump + Redis RDB，产物 `/opt/swap/backup/{mysql,redis}`，**14 天保留**）。
- 恢复演练做过：临时库 `swap_ops_restore_check` 导入 → 行数区间断言 → drop（源库不动）。
- 两个历史坑（已在仓库版修复）：`mysqldump` 缺 `-u"$MYSQL_USER"`；`backup.env` 变量未 `export`（crontab 子进程读不到）。

### 3.6 云上与本地的已知差异

| 维度 | 现状 | 待办 |
|---|---|---|
| 代码版本 | 落后 11 批次（含 4 个已修真实缺陷未生效：分页全表、下行 chunked 读空、事件未回带 `commandSeq`、`RateLimitAspect` NPE） | 见交接文档 B1/B2 |
| 迁移 | 已到 `db/15`，缺 `db/16`/`db/17`（与代码自洽，**非故障**） | 随 B1 一并补，DDL 先确认 |
| 前端 | 未部署；nginx `inactive/disabled`；`/etc/nginx/sites-enabled/` 残留 `club-agent`/`grab-system` 旧站点 | 上 nginx 前先清残留，否则反代冲突 |
| MQ | 未装（HTTP 通道） | 需先做内存评估（available ~1.7Gi） |
| 监控 | 未接常驻 Prometheus（jar 太旧，指标类不存在） | 随 B1 生效，可选接 Grafana |
| 暴露面 | ufw 仅 22 | 是否开 80/443 **待用户拍板** |

---

## 4. 一次完整块循环（把三条链路串起来）

```
讨论方案 → 实施
  → 验证：单测（mvn clean verify -DskipITs）+ 剧本（本地全栈起平台，跑 _cNN）
  → 文档：block-record + 必要时 pitfalls/知识卡 + 索引回填（README/verify README/story-cards）
  → 本地全量绿 → git add/commit(-F 文件)/push
  → CI 监视至绿（§2.4，两个 job 都要看）
  → 云上同步与验收（§3.3-B + §3.4；DDL 与破坏性动作先向用户确认；缺这步该块不算收口）
```

顺序依据：代码与测试同批；纯文档攒批；云上验收不可跳。
