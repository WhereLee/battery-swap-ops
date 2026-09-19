# 坑位：剧本重构后少跑检查，而证据文件仍是旧的（且失败被伪装成 0ms 断言）

> 发现：批次38 ｜ 影响面：**所有脚本化门禁的可信度**（不是应用缺陷，是"证据缺陷"）
> 一句话：重构把 6 项检查删掉了、把 9 项检查变成了 0ms 立即失败，而仓库里的 `_c35_out.txt` 还在宣称 31/31。

## 现象

批次38 收口时按计划重跑 `_c35_console.ps1`，结果是 **PASS=16 FAIL=9**，而文档基线写的是 31/31。
9 条失败长这样（没有任何诊断信息）：

```
FAIL  B08 work-order detail opens from the list and renders the flow log
FAIL  B11 order detail opens from the list and renders payments/refunds cards
FAIL  B13/B14 cabinet detail ...   FAIL  B16/B17 settlement detail ...
FAIL  B18/B19/B20 route guard + redirect round trip
=== C35 summary: PASS=16 FAIL=9 ===
```

同一批失败里既有"点进去没渲染"又有"路由守卫没跳转"，看起来像应用整体坏了；
但截图里详情页只有 29–32 kB（列表页是 92–129 kB）——**页面根本没渲染**，因为**根本还没导航过去**。

## 三个独立成因（同一个症状）

### 1. `waitFor` 的第二个参数被当成超时时间，而调用方传的是标签

`_cdp.mjs` 抽出后签名是 `waitFor(expression, timeoutMs = 15000)`，而 `_c35_console.mjs` 的调用点沿用老签名：

```js
const { evaluate, waitFor, goto } = session;      // 拿到的就是 session.waitFor
...
await waitFor("...", "work-order detail");        // 本意是"等不到就打印这个名字"
```

`deadline = Date.now() + "work-order detail"` ⇒ `NaN`；`while (Date.now() < NaN)` 恒 false ⇒
**第一次判断就返回 false**。9 项检查在毫秒级全红，且 `FAIL` 行不带任何细节。

### 2. 重构静默删掉了 6 项检查

`_c35_console.mjs` 现在只有 25 个 `check()`（B01–B25），而提交里的 `_c35_out.txt` 有 31 行 PASS——
多出来的是 6 条 `B goto /alarm|/work-orders|/orders|/cabinets|/settlements|/orders renders (...)`：
老的本地 `goto` 包装器把每次页面落地**当成一项检查**，重构进 `_cdp.mjs` 后这段没了，
**覆盖面从 31 掉到 25，而所有文档仍在写 31**。

### 3. 证据文件根本不是脚本产出的

`_c35_console.ps1` 只把 `node _c35_console.mjs` 的输出**打印**出来，`_c35_out.txt` 是当时人工 `Tee-Object` 的一次性产物。
所以"文件说 31、脚本只跑 25"这件事**没有任何命令能发现**——`git log` 里脚本只有一个提交，
证据文件与脚本同批入库，看上去严丝合缝。

## 修法（三条，缺一不可）

1. **错误类型不得伪装成断言失败**：`session.waitFor` 对非有限/非正数超时**抛错**：

   ```js
   if (typeof timeoutMs !== "number" || !Number.isFinite(timeoutMs) || timeoutMs <= 0) {
     throw new Error(`waitFor(timeoutMs) must be a positive finite number, got ${typeof timeoutMs} ...`);
   }
   ```
   同样的错误若再犯，是 FATAL 而不是 9 条 FAIL。
2. **恢复检查并用显式签名**：`waitFor(expression, label, timeoutMs = 15000)` 包装器（标签只用于日志），
   两个原本传裸超时的调用点补上标签；`navCheck(path, ready, label)` 把 6 条导航检查加回来 ⇒ 25 + 6 = **31**。
3. **剧本自写证据**：`_c34` 增加 `Emit`/`Save-Evidence`（每个退出路径都落盘），
   `_c35`/`_c38` 的 `.ps1` 包装器捕获子进程输出并以 UTF-8 无 BOM 写 `_*_out.txt`。
   从此"记录"与"产生记录的命令"不可能脱节。

## 复现与验证

```powershell
# 修前：9 条 0ms 失败（无诊断）
# 修后：
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify\batch31\_c35_console.ps1
#   ... GATE-BROWSER PASS / === C35 summary: PASS=31 FAIL=0 ===
#   INFO evidence written: scripts/verify/batch31/_c35_out.txt
```

另有一条同源教训：**找不到证据文件不等于没跑过**，反过来 **有证据文件也不等于跑过**——
判据是"这条命令能不能重新生成它"。

## 通用规则（写给后续批次）

- 断言辅助函数的**参数类型必须校验**，尤其是"可选的第二个参数"；
- 重构跨文件抽取公共代码时，**先数 check 数量**（`Select-String '^\s*check\('`），改完再数一次；
- **证据文件由脚本自己写**，不接受人工 tee；
- 文档里的分母（31/31、59/59、13/13）必须能由某条命令复现，否则就是"精确但无出处的数字"。
