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
