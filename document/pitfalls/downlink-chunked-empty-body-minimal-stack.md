# 坑位：JDK 客户端默认 chunked + h2c 下行 → 最小设备栈（Python 标准库）把请求体读成空

> 状态：已修复（2026-09-17，批次26）| 证据：`scripts/verify/batch26/_c30_out.txt`（首跑 11/16 → 修复后 25/25）、探针原始请求 dump、`CommandDispatchServiceTest` 新增报文形态断言

## 现象

- `_c30` 异构对接首跑：下单 400，平台日志 `开仓指令下发失败 … cause=柜拒绝指令: not my cabinet: None`；
- **设备端日志毫无请求痕迹**（连验签拒绝行都没有），订单被补偿关闭；
- 同一平台与 Java sim（Tomcat）配合一切正常——"平台没问题，设备的问题"的直觉是错的。

## 根因链

1. 平台下行用 `JdkClientHttpRequestFactory`（包装 `java.net.http.HttpClient`）——
   body 以流式（长度未知）方式提交 → **HTTP/1.1 chunked 编码，无 Content-Length**；
2. 同时 JDK HttpClient 默认尝试 **h2c upgrade**（`Upgrade: h2c`）——对简单服务器是无意义的协商噪音；
3. 设备端是 Python 标准库 `http.server`：**只解析 Content-Length，不解析 chunked 请求体** →
   读到空 body → `payload={}` → `cabinetNo=None` → 设备回 `code:1 not my cabinet: None`；
4. 报文其实**完全符合 HTTP/1.1**——但真实世界的嵌入式/脚本设备栈大多只实现"定长 body"这一子集；
5. 为什么长期没暴露：sim=Tomcat、单测=JDK HttpServer、第三方契约客户端只打上行——
   **没人扮演"最保守设备"**。

## 修复

- `CommandDispatchService.postJson` 手写 `java.net.http` 调用：
  - `HttpClient.Builder.version(HTTP_1_1)`（不发起 h2c 协商）；
  - `BodyPublishers.ofString(json, UTF_8)`（**自带 Content-Length**）；
  - 保留原异常语义映射（`RestClientException` / `HttpStatusCodeException` 分级不变）；
  - 顺带修正：JDK `builder.header` 不接受 null 值（traceId 可缺失）——跳过而非传入。
- 回归门禁（单测）：响应侧断言 `Content-Length == body 字节数` 且**无** `Transfer-Encoding` 头。

## 可复用教训

- **面向设备的报文形态要显式化**：接入方必须在"最保守设备栈"的假设下选传输形式（定长 body、HTTP/1.1），
  框架默认行为（chunked、h2c 协商）只适合"对端也是富裕 HTTP 栈"的服务间调用；
- **探针假设备（dump 原始请求）是排查"平台↔设备"协议纠纷的最快手段**——比两侧读代码猜快一个数量级；
- **"标准合规"≠"现实兼容"**：chunked 是合法 HTTP，但设备端不会解析它。
  测试矩阵里至少有一个"最小实现"角色，否则中间的舒适区会掩盖缺陷；
- 复现要点：`Transfer-Encoding: chunked` + 无 `Content-Length` + 对端只读定长 body = 空报文，且**无任何报错**（静默）。
