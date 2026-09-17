# 坑位：PowerShell 管道接壳启动长活 JVM → `| Out-Null` 永不返回（剧本假死）

> 状态：已修复（2026-09-17，批次26）| 证据：`scripts/verify/batch26/_c30_hetero_device.ps1`（修复后 `restored [verified=True]`）；对照实验：同脚本无管道 0.34s 返回、有管道拖到 JVM 退出（快退 java 耗时 20.8s）

## 现象

- 剧本恢复段 `powershell -File start-server-mq.ps1 | Out-Null` **永不返回**——
  测试工具 900s/1200s 两级超时都杀不完；可观察到"平台已起、sim 未起、剧本停在恢复段"；
- 同样的启动脚本**手动裸跑**（无管道）0.34s 就返回。

## 根因链

1. `cmd | Out-Null` 的完成条件是**管道 EOF = 所有写端关闭**；
2. 启动脚本内部 `Start-Process javaw ...`（经 Oracle `javapath` shim 转发）——
   尽管 stdout/stderr 已 `-RedirectStandardOutput` 到文件，**被启动的 JVM 进程仍持有可继承的管道句柄**
   （实测：管道形式下外层要等 java 进程退出）；
3. 平台是长活进程 → 写端永不释放 → 管道永不 EOF → 剧本永久挂起；
4. 对照实验把变量锁死：**同一脚本，无管道 0.34s；有管道=等 JVM 死活**
   （java 因端口冲突快退时 20.8s，等于 JVM 生命周期）。

## 修复

- 启动**长活进程**的脚本一律**裸调用，不接管道**（`powershell -File start-xxx.ps1`）；
- 恢复动作后不靠"启动命令已返回"判断成功，而是**有界等待真实健康**：
  平台 `:8400` + sim `:8500` 双 `actuator/health=UP` 才打印 `restored … [verified=True]`；
- 附带加固：清理段去 WMI 依赖（`Get-CimInstance Win32_Process` 过滤改为 netstat 端口法——更快且无环境依赖）。

## 可复用教训

- **PowerShell 管道等的是"写端全关"而不是"命令退出"**——任何"启动常驻进程"的调用都不要进管道
  （`| Out-Null` 同样挂）；`Start-Process` 本身的"消失点"不能作为成功信号；
- 断言"启动完成"的正确姿势是**探测目标服务的可观测状态**（health/端口），而不是相信启动命令；
- 排障口诀：**先对照实验锁变量**（无管道 vs 有管道、快退 vs 长活），比通读脚本快得多
  （本案从"猜了很多嫌疑"到定案只花了两个对照实验）。
