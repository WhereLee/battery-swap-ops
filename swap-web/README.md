# swap-web — 换电运营平台管理台（S8）

Vue 3 + Vite + TypeScript + Element Plus + Pinia。读平台 BFF 视图层（`/admin/view/**`），
写操作仍调各域端点（`/admin/alarm`、`/admin/work-order`、`/admin/agent-action`）。

设计冻结见 [`../document/plans/S8-前端管理台与BFF视图层-方案.md`](../document/plans/S8-前端管理台与BFF视图层-方案.md)。

---

## 1. 本地开发

前置：平台在 `:8400` 运行（`../.local/run-server.bat`），MySQL/Redis 已起。

```bash
npm install        # 首次
npm run dev        # http://localhost:5173 ，/api 由 Vite proxy 转发到 :8400
```

登录凭据不入仓：管理员密码在 `../.local/admin-pass.txt`（gitignored）。

## 2. 脚本

| 命令 | 作用 | 是否门禁 |
|---|---|---|
| `npm run dev` | 开发服务器（HMR） | — |
| `npm run type-check` | `vue-tsc --noEmit`，全量类型检查 | ✅ CI |
| `npm run build` | `vue-tsc --noEmit && vite build` → `dist/` | ✅ CI |
| `npm run preview` | 预览构建产物 | — |
| `npm run gen:api` | 从 `../document/api/openapi.json` 生成 `src/api/schema.d.ts` | 手动 |

`gen:api` 产物是**构建物**（已 gitignore）。页面依赖的类型是手写的 `src/api/types.ts`
（与后端 `web/view/AdminViews.java` 的 record 一一对应）；两者分歧时 `type-check` 会在调用点失败，
这就是"手写类型 + 生成类型"并存时的漂移检测方式。

## 3. 权限模型（三处必须一致）

| 层 | 位置 | 语义 |
|---|---|---|
| 后端（**唯一安全边界**） | `@PreAuthorize("hasAuthority('admin:x:y')")` | 403 |
| 路由 | `router/index.ts` 的 `meta.codes` | 守卫 any-of 校验，不匹配 → `/403` |
| 按钮 | `v-access="'admin:x:y'"` 指令 | `display:none`（UX，不是安全边界） |

- 权限码**不在前端硬编码角色映射**：登录后由 `GET /admin/auth/me` 下发 `codes[]`（源自
  `AdminRole.permissions()`），前端只做集合判断。
- 所有码集中在 `src/api/permissions.ts`（字面量）。后端 `PermissionCodeContractTest`
  会读这个文件，断言"前端所用码 ⊆ `AdminRole.ALL_CODES`"——**漂移即 CI 变红**。
- 菜单由路由表派生（`menuEntries()`），不另维护一份菜单配置，避免"可路由但不可见"。
- 数据范围（`dataScope=STATION` 的受限身份）在顶栏显式标识，看板页额外给出警示条：
  受限身份看到的指标是本域口径，不等于全网。

## 4. 能力位（allowedActions）

按钮显不显示由**后端**决定：视图接口每行都带 `allowedActions`（由
`common/action/ActionsSupport` 从状态码推导，动作码与端点动作段字面一致）。
前端只判断 `row.allowedActions.includes('confirm')`，**不复制状态机规则**。
跨资源可用性（如"该告警是否已有工单"）也由后端在视图层算好。

## 5. 网络与部署形态

**同源策略，后端不开 CORS**：

- 开发期：Vite proxy `/api` → `http://127.0.0.1:8400`；
- 生产：nginx 托管 `dist/` + 反代 `/api`（批次31 落地）。

Token 走 `X-Admin-Token` 头，存 `localStorage`（键 `swap.admin.token`），
Axios 拦截器统一注入；401 → 清态并跳登录（保留 `redirect`），403/429（读 `Retry-After`）/5xx 分级提示。

响应契约：`Result{code,msg,data}`（`code=0` 成功）、`PageResult{list,total,page,limit}`。

## 6. 目录

```
src/
├── api/          http.ts(拦截器) types.ts(VO 类型) views.ts(接口封装) permissions.ts(权限码)
├── directives/   access.ts        v-access 指令工厂
├── layouts/      DefaultLayout.vue 侧边菜单 + 顶栏（角色/数据范围/登出）
├── router/       index.ts         路由表 + meta.codes + 全局守卫 + menuEntries()
├── stores/       auth.ts          token / me / codes / hasAnyCode
├── styles/       global.css
├── utils/        format.ts        时间(epoch millis)/比率(0..1)/金额(分) 格式化
└── views/        Login / Dashboard / Alarm(告警+建议单) / Forbidden / NotFound
```

## 7. 约定与边界

- 时间字段一律 **epoch millis**（服务端 `System.currentTimeMillis()`）；比率一律 **0..1**
  （`formatPercent` 负责 ×100）；金额一律 **整数分**（不让 float 碰钱）。
- **不做**（有意取舍，见方案 §7）：动态菜单表（sys_menu）、i18n、主题定制、自研组件库、
  微前端、SSR、前端单测（只保留 `type-check` + `build` 两道门禁）、图表大屏。
- Element Plus 全量引入 + `manualChunks` 拆 vendor（主入口 64 kB / element 940 kB，gzip 302 kB）。
  构建时的 chunk 体积警告**故意保留不掩盖**：内网管理台对首屏不敏感，按需引入需额外两个构建插件，
  收益不兑现于当前部署形态。

## 8. 已落地的实机验证

- 剧本 `../scripts/verify/batch30/_c33_paging.ps1`：13 个分页端点 × 5 类断言（81 PASS / 0 FAIL / 1 SKIP）。
- 浏览器实机联调（1440×900）：登录 → 看板 → 告警翻页/改页长 → 建议单驳回 → 404 → 登出 → 守卫拦截，
  控制台 0 error。这次联调直接抓出了后端存量缺陷（分页拦截器缺失，
  见 `../document/pitfalls/mp-pagination-interceptor-missing.md`）。
