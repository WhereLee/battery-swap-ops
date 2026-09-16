# 坑位：PowerShell 5.1 写文件带 BOM / 默认 GBK 读 UTF-8 导致源码损坏

> 状态：已解决（2026-09-11，S2 测试文件加固时踩中）
> 环境：Windows PowerShell 5.1（默认脚本代码页 GBK 936）

## 现象

1. `Set-Content -Encoding UTF8` 重写 Java 测试文件后，编译报：
   `错误: 非法字符: '\ufeff'`（文件头 BOM）→ `需要 class/interface/enum/record`。
2. 同一命令链里 `Get-Content -Raw`（未显式 `-Encoding`）把 UTF-8 中文按 GBK 解码，
   再写回时中文变成 `妯℃嫙鏌滃崟娴嬶細...`（经典 GBK 乱码），即使无 BOM 也毁文件。

## 根因

- PS 5.1 `-Encoding UTF8` = **带 BOM 的 UTF-8**（PS Core 6+ 才是无 BOM）；
- `Get-Content`/`Set-Content` 未显式指定编码时使用 ANSI（GBK）——中文源码/注释必然损坏；
- javac 不接受源码 BOM。

## 处置（当前项目已用）

- 源码/文档一律用专用写文件工具（UTF-8 无 BOM），**不用 PS 管道改写文本文件**；
- 如已损坏：读取原字节 → 校验 `239,187,191` BOM → 字节级剥离 `bytes[3..]` 写回（不经过字符串解码）；
- 若内容已乱码：从版本库/备份重写整文件（本项目本次即整文件重写）。

## 教训

1. Windows 上碰中文文件：要么显式 `[System.IO.File]::ReadAllBytes/WriteAllBytes`，
   要么用能固定无 BOM UTF-8 的专业工具；绝不裸用 `Get/Set-Content`。
2. 脚本（.ps1）内容保持全英文（本工作区既有约定）——既避开控制台乱码，也避开正则字面量失效。
3. javac 报 BOM 时不要怀疑编译器：先看文件前三字节。
4. **补充实证（2026-09-12，S4.5 `_c9` 剧本）**：无 BOM 的 UTF-8 `.ps1` 中出现中文字面量时，
   PS 5.1 按 ANSI 解码可触发**解析错误**（如"字符串缺少终止符"），且报错行号指向文件末尾、误导排查；
   处置：剧本内所有字面量改英文（本次 `联调套餐`→`E2E-PLAN-`）。这是第 2 条不是洁癖而是硬约束的又一证据。
5. **补充实证（2026-09-16，批次21 校验剧本）——「必须断言中文」时的正解**：
   P1-7 断言的就是产品输出的中文消息（"参数校验失败/幂等/订单不存在"），改英文不可行。
   正解：把 `.ps1` 转存为 **UTF-8 with BOM**（PS 5.1 对有 BOM 文件按 UTF-8 解析，中文安全）：
   `$c = Get-Content -Raw -Encoding UTF8 $p; [IO.File]::WriteAllText($p, $c, (New-Object Text.UTF8Encoding($true)))`。
   注意：转换前用 `-Encoding UTF8` 显式读源文件（无 BOM 源按默认读也会乱码二连击）；转后中文断言 13/13 PASS。
   规则收敛：**英文文案剧本可无 BOM；含中文字面量的 .ps1 一律 UTF-8 with BOM**。
6. **附属坑（同批次，HTTP 断言）**：PS 5.1 `Invoke-WebRequest -OutFile` 的返回对象 **取不到 `.StatusCode`**
   （`[int]$null`=0，静默把 200 断成失败）；且默认 `.Content` 对无 charset 的 JSON 按 ANSI 解码，中文断言必乱。
   正解：`-OutFile` 落盘后用 `Get-Content -Raw -Encoding UTF8` 读 body；状态码在成功分支回退 200
   （IW 对 4xx/5xx 抛异常已天然分流），失败分支用 `StreamReader(resp.GetResponseStream(), UTF8)` 读错误体。
