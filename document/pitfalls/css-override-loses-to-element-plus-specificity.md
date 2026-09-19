# 坑位 · CSS 覆盖输给 Element Plus 的特异性时，是**静默**的

> 发现于批次43 补记（输入计数器 `0 / 200` 对比度修复）
> 一句话：**改样式改的是"级联结果"，不是"源码看起来对不对"；特异性高于顺序时，写在后面的规则照样不生效，而且没有任何报错。**

## 一、现象

`_c38` 弹窗轮在驳回建议单里量到：

```
low text 3.08:1 (need 4.5) "0 / 200" span.el-input__count color=rgb(144, 147, 153)
```

`#909399` 是 Element Plus 的 `--el-color-info`。于是按既有做法（批次38/39 都是"令牌/组件级覆盖"）写了：

```css
/* global.css（第一版，无效） */
.el-input__count,
.el-input__count-inner {
  color: var(--el-text-color-secondary);
}
```

`npm run build` 通过、产物里确实有这条规则、位置也确实在厂商 CSS **之后**——重新跑门禁，**仍然报 3.08:1**。

## 二、原因

Element Plus 的规则不是单类选择器：

```css
.el-input .el-input__count { color: var(--el-color-info); ... }        /* (0,2,0) */
.el-textarea .el-input__count { color: var(--el-color-info); ... }     /* (0,2,0) */
.el-input.is-exceed .el-input__suffix .el-input__count { ... }         /* (0,4,0) 危险色 */
```

而 `.el-input__count` 只有 **(0,1,0)**。**特异性优先于顺序**，所以"写在后面"完全救不了它。
更糟的是失败形态：**没有报错、没有警告、构建产物里能搜到这行 CSS、lint 也过**——
只有"重新测量"能发现它没生效。

产物自证（同一份 `index-*.css` 的字节偏移，可复现）：

```
178070  .el-textarea .el-input__count{color:var(--el-color-info); ...}
180439  .el-input .el-input__count{height:100%;color:var(--el-color-info); ...}
184206  .el-input.is-exceed .el-input__suffix .el-input__count{color:var(--el-color-danger)}
361996  .el-input__count,.el-input__count-inner{color:var(--el-text-color-secondary)}   <-- 无效（第一版）
361980  .el-input .el-input__count,.el-textarea .el-input__count{color:var(--el-text-color-secondary)}  <-- 生效
```

## 三、正确做法

1. **覆盖厂商组件样式时，先看厂商那条规则的选择器，再决定自己写多长**：
   打开产物 CSS（或 DevTools 的 Computed/Matched rules）确认赢家是谁，不要凭"我写在后面"下结论。
2. **对齐权重、保持顺序**：`global.css` 在 `element-plus/dist/index.css` 之后导入，所以**同等权重**即够用；
   本仓库批次38 的标签覆盖（`.el-tag--info:not(.el-tag--dark)` 对 EP 的 `.el-tag.el-tag--info`）用的就是这个办法，
   并在注释里写明了"改导入顺序会让整块静默失效"。
3. **状态类的规则照抄厂商的形状**：`.is-exceed` 那条 EP 用 (0,4,0)，就写同样形状的选择器，
   否则"超限变红"会被自己的修复反杀。
4. **只用"重新测量"验收**：本坑位是靠门禁复跑发现的，不是靠读代码。**凡是"改样式"的修复，
   验收标准是门禁的测量值变了，不是文件内容看着对**。
5. 与本仓库既有自省同源（`document/pitfalls/check-coverage-lost-in-refactor.md`）：
   **"看起来改好了"和"实测变了"是两件事**；批次41 的"占位符样本加在 `lowContrastText` 计算之后"也是同一个错误的另一种外形。

## 四、可复现命令

```powershell
cd battery-swap-ops\swap-web
npm run build
# 在产物里定位我方规则与厂商规则的先后（字节偏移）
$css = Get-ChildItem dist\assets\index-*.css
$t = Get-Content $css.FullName -Raw
[regex]::Matches($t, '.{0,110}\.el-input__count[^{}]{0,40}\{[^}]{0,90}\}') | ForEach-Object { "@$($_.Index) $($_.Value)" }
# 然后必须跑门禁（唯一可信验收）
& powershell -NoProfile -ExecutionPolicy Bypass -File ..\scripts\verify\batch31\_c38_ui_probe.ps1
```

## 五、附带结论（本坑位暴露的面）

同一个缺陷类（**用基色当文字色压在自己的浅色底上**）在本仓库已经出现三次：
批次38 的 `.el-tag`、批次39 的 `--el-text-color-secondary`（整个次要文字层）、批次43 的 `.el-alert` 与计数器。
Element Plus 的默认主题在"12px 文字"这一档上系统性地不达 WCAG AA——
**组件级覆盖是治标，逐组件清点才能收敛**，而"逐组件清点"的前提是门禁能看到那些组件
（所以才有批次43 的校验态通道与变体矩阵）。
