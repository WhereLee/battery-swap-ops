# 坑位：满高布局下 `captureBeyondViewport` 只截到一屏（"整页截图"是假的）

> 发现：批次38 ｜ 影响面：**截图类证据的有效性**（视觉评审、文档配图、面试演示图）
> 一句话：控制台外壳内容在 `<el-main>` 内部滚动，`documentElement.scrollHeight` 恒等于视口高，
> 于是"整页截图"实际只有 1440×900，结算详情 48 行分账流水**只截到第 12 行**。

## 现象

`_cdp.mjs` 的 `screenshot(path, { fullPage: true })` 用 `Page.getLayoutMetrics().cssContentSize` 当裁剪高度，
再加 `captureBeyondViewport: true`。逻辑看着没错，但产出的 **11 张截图高度全部正好 900**：

```
01-login 1440x900   02-dashboard 1440x900   03-alarm 1440x900
... 11-settlement-detail 1440x900   （而该页有 48 行流水 + 守恒校验块）
```

视觉复核直接报出："图片实为 1440×900 视口截图而非整页长图，分账流水表在第 12 行处被截断"。

## 根因

探针量出来的事实：

```
INFO viewport=1440x900 document=1440x900 contentHeight=2049 scrollers=[main.el-main:840<2049]
INFO viewport=1440x900 document=1440x900 contentHeight=1899 scrollers=[main.el-main:840<1899]
```

- 布局是"满高外壳"：`header` 固定高，`main.el-main` 自己 `overflow:auto`，
  **文档本身永远只有一屏高**；
- 所以 `documentElement.scrollHeight === 900`，`cssContentSize.height === 900`，
  `captureBeyondViewport` 没有任何"视口之外"可截；
- 页面真正的高度（`main.scrollHeight`）只有问**滚动容器**才知道。

## 修法：扩视口到不动点，再截，再还原

`_cdp.mjs` 截图前先算"内容高 + 外壳占高"，把模拟视口**迭代**放大到容纳全部内容：

```js
let target = viewport.height;
for (let attempt = 0; attempt < 4; attempt++) {
  const probe = JSON.parse(await session.evaluate(MEASURE_CONTENT_HEIGHT));
  const chrome = probe.scrollerClientHeight > 0 ? target - probe.scrollerClientHeight : 0;
  const need = Math.max(probe.contentHeight + chrome, viewport.height);
  if (need <= target) break;                    // 不动点
  target = Math.min(need, 20000);
  await cdp.send("Emulation.setDeviceMetricsOverride", { width, height: target, ... });
  await sleep(400);
}
// getLayoutMetrics → clip → captureScreenshot → 还原 1440x900
```

为什么要迭代而不是"内容高 + 常数"：**外壳高度不是常数**（表头/面包屑/内边距都可能变），
一次算不对就少截最后一屏的尾部（例如结算详情会少最后 60px，正好是页脚那行结论）。
迭代到 `need <= target` 是自证的。

## 复现与验证

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify\batch31\_c35_console.ps1
# 修后（截图行把真实尺寸打出来，便于核对）：
#   INFO  screenshot 03-alarm.png (200 kB, captured 1440x1404)
#   INFO  screenshot 09-cabinet-detail.png (158 kB, captured 1440x1959)
#   INFO  screenshot 11-settlement-detail.png (272 kB, captured 1440x2109)
# 短页面仍为 1440x900（本来就一屏放得下，不需要扩）
```

视觉复核复测：结算详情 48 行流水与守恒校验块（`代理 + 平台 = 基数`）都在图内；
换电柜详情底部"未处理告警 / 进行中订单"两个面板的表头也完整可见。

## 连带更正（诚实记录）

批次31 曾把视觉评审的"内容在折线处被截断"整体判为**截图口径造成的假阳性**（理由是 `--window-size` 是外层窗口尺寸，
实得 1414×800）。批次38 的测量显示这个判定**只对了一半**：

- "列被滚动条压住、横向少 14px"——确实是外层窗口尺寸造成的假阳性（DOM 实测横向溢出 0）；
- **"内容被截断"是真的**：内容确实没进图里，只不过成因不是窗口尺寸而是**内部滚动容器**。

教训：把一批抱怨整体归类为"假阳性"是危险的——同一份清单里可以同时有真缺陷与假阳性，
必须**逐条落到一个可测量的量**上。

## 通用规则

- 说"整页截图"之前，先量**谁是滚动容器**（`scrollHeight > clientHeight` 且 `overflow-y: auto|scroll`）；
  满高布局（`el-container`/`height:100%`/抽屉式后台）里 `documentElement` 通常不是它；
- 截图辅助函数应当**返回真实捕获尺寸**，并要求调用方把它打进证据文本（本次即如此），
  否则"整页"这两个字永远无法被核对；
- 扩视口后必须**还原**，否则后续 DOM 测量看到的是被放大的视口（本批所有页面测量都要求 1440×900）。
