# 知识：S5 质量与交付（覆盖率门槛 + 容量/GC 画像）

> 证据：`scripts/verify/batch7/_cov_out.txt`、`_load_out.txt`；大件（jtl/gc.log）在 `diag-archive/`（不入 git）。

## 1. 覆盖率门槛（S5.1，先测后定）

| 模块 | 实测（line） | 门槛 | 说明 |
|---|---|---|---|
| swap-server | 69.0% | **65%** | 关键业务逻辑（服务/CAS/对账）覆盖较高；控制器/配置类为已知低覆盖区 |
| swap-sim | 61.1%（补测前 46%） | **55%** | 补 `CommandControllerTest`/`SimRegistryTest` 覆盖指令路由与 fail-fast 装载 |
| swap-contract | 77.2% | **70%** | 契约向量为主 |

- 门槛写在各模块 `jacoco.line.minimum`，父 POM 在 `verify` 阶段执行 `jacoco:check`（CI 跑 `mvn verify`）；
- 定门槛方式：**先跑基线再定**（不先拍数字），留 ~3pt 余量防波动；只升不降；
- 已知低覆盖区（如实声明）：Controller/Interceptor 薄层、Config 类、异常分支。

## 2. 容量测试（S5.2，JMeter 非 GUI）

- 计划：`scripts/verify/batch7/jmeter/swap-ops-load.jmx`（20 线程 × 30 循环 × 4 请求 = 2400 样本；
  登录 + `/user/plans` + `/user/stations` + `/user/wallet` 读路径）；
- 运行环境：本机单实例，heap 固定 512m，**限流关闭**（`run-server-load.bat`，容量测的是平台本身而非限流器，文档声明）；
- 结果：**吞吐 465.5/s，0 错误；avg 5.2ms / p95 11ms / p99 14ms / max 35ms**；
  按接口：stations 最重（avg 9.1ms，分配池多柜计数），plans 最轻（缓存命中，avg 3.1ms）。

## 3. GC 画像（S5.3）

- 参数：`-Xlog:gc:file=gc.log:time,uptime`，512m 固定堆；
- 结果：整轮压测 **16 次 GC，总暂停 68.5ms，最大暂停 9.9ms**（Young 为主）——单次 <10ms，读路径无长暂停风险；
- 结论：**512m 堆对本容量（~465 QPS 读）足够**；生产建议按实例规格压测复核（示例数据量远小于生产）。

## 4. 这两项"证明了什么/没证明什么"（如实边界）

- 证明：单机读路径吞吐/延迟基线、GC 暂停量级、覆盖率门槛可执行（CI 强制）；
- 未证明：写路径（下单/支付/事件链）容量、多实例扩展性、长稳（>1h）与内存泄漏趋势、生产数据量级下的表现——
  归后续压测/云环境（S5 余项）。

## 5. 复现命令

```powershell
# 覆盖率（含门槛校验）
mvn -B -ntp clean verify
# 容量（先起 load server：.local\run-server-load.bat）
jmeter -n -t scripts/verify/batch7/jmeter/swap-ops-load.jmx -l ..\diag-archive\s5-load-result.jtl
# GC 摘要（PowerShell 一行：见 _load_out.txt 生成脚本）
```
