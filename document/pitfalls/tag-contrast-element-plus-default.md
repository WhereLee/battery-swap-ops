# 坑位：Element Plus 默认标签对比度不达 WCAG AA（12px 小字尤其致命）

> 发现：批次38 ｜ 影响面：**所有用 Element Plus `el-tag` 做状态展示的管理台**
> 一句话：默认浅色 tag 把**语义基色当文字**压在 light-9 浅底上，实测 **2.04–2.80:1**；
> dark 效果白字压基色 **3.08:1**。12px 属"正常字号"，WCAG 1.4.3 要求 **4.5:1**。

## 实测数据（`_c38` 探针读渲染后的前景/背景，按 WCAG 相对亮度公式计算）

| 效果 | 文字色 | 背景色 | 实测比值 | 判定 |
|---|---|---|---|---|
| light success | `#67c23a` | `#f0f9eb`（success-light-9） | **2.08:1** | ✗ |
| light warning | `#e6a23c` | `#fdf6ec` | **2.04:1** | ✗ |
| light danger | `#f56c6c` | `#fef0f0` | **2.61:1** | ✗ |
| light info | `#909399` | `#f4f4f5` | **2.80:1** | ✗ |
| dark info（控制台角色徽标） | `#ffffff` | `#909399` | **3.08:1** | ✗ |

对照：`--el-fill-color-light`（`effect="plain"` 的底）近似白，情况同样不达标。

## 为什么是"致命"而不是"略低"

标签字号是 **12px**（`--el-tag-font-size: 12px`），属 WCAG 的**正常字号**，门槛 4.5:1 而非大字的 3:1。
2.04:1 的红/绿/黄底小字在 1440p 屏幕上**读不出来**——复审时视觉模型把这类标签描述成
"渲染成空白方块/没有文字"。它的**结论方向是对的**（用户看到的确实是一片色块），只是把"看不清"说成了"没渲染"。
这也说明：**"标签是不是坏了"这种问题，看图的答案和量 DOM 的答案会不一样**，必须以 DOM 为准。

## 修法：保色相、压深度（`swap-web/src/styles/global.css`）

浅色 tag 只改**文字**、保留浅底（底色是状态信号）；dark tag 只改**底色**、保留白字。同一套 5 个色板两用：

```css
.el-tag--success:not(.el-tag--dark) { --el-tag-text-color: #2f6b1c; }   /* 6.00:1 on #f0f9eb */
.el-tag--dark.el-tag--info { --el-tag-bg-color: #4c4f57; }              /* 8.19:1 under #fff */
```

实测结果：**270 个标签 6.00–8.57:1，0 个不达标**（门禁阈值同时从 3:1 提到 4.5:1，**不放宽标准去迁就实现**）。

## 两个必须知道的实施细节

1. **CSS 优先级刚好打平**：Element Plus 写的是 `.el-tag.el-tag--success`（0-2-0），
   上面的选择器 `:not()` 计入实参特异性后**同为 0-2-0**，靠的是 `main.ts` 里
   `import "./styles/global.css"` 排在 `element-plus/dist/index.css` **之后**。
   **改动导入顺序会让整套覆盖静默失效** —— 这正是 `_c38` 门禁存在的意义（它量渲染结果，不读源码）。
2. **不要用 `status` 属性给进度条上色**：`el-progress` 一旦设了 `status`，会**用状态图标替换百分比文字**
   （见 `document/pitfalls/` 同批的 AUD-9：换电柜 SOC 列因此 12 行一个数字都不显示）。
   要上色用 `:color`。

## 复现

```powershell
cd swap-web; npm run build
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify\batch31\_c38_ui_probe.ps1
#   PASS  every visible tag reaches 4.5:1 contrast (0 below)
#   === C38 summary: pages=10 hardFailures=0 ===   GATE-UI-PROBE PASS
```

## 通用规则

- 组件库的"默认好看"不等于"可访问"；**默认配色必须实测对比度**，尤其是 12px 这类小字；
- 门禁阈值取**标准值**（WCAG 4.5:1），不取"看起来还行"的经验值（3:1 会放过 2.8:1 的灰字）；
- 对比度修复要**保色相**（用户靠颜色识别状态），只压深度，不要改成统一黑字。
