# 坑位：GBK 平台编码吞掉 UTF-8 源码（Lombok getter 诡异地少一个）

> 状态：已解决（2026-09-11，S1 首次编译暴露）
> 环境：Windows + javac 17 + Maven，控制台/平台编码 GBK

## 现象

编译报"找不到符号：方法 getBootId，位置：类型为 DeviceEventForm 的变量 form"——**只有这一个 getter 报错**，
其余字段（getEventSeq 等）正常；class 文件里甚至存在 getBootId（上一轮残留）造成"薛定谔的 getter"。

## 根因

- 源码是 UTF-8（含中文注释），而 Maven 未显式声明 `project.build.sourceEncoding` 时，javac 按**平台编码 GBK**
  读取 UTF-8 字节；
- GBK 是双字节编码，错位解析后，某条中文注释结尾的 `*/` 被吞掉，**紧随其后的字段声明被并入注释**；
- Lombok 看不到该字段 → 不生成 getter；javac 只在业务类里报"找不到符号"，误导排查方向。

## 处置

父 POM 显式固定编码（子模块继承）：

```xml
<properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
</properties>
```

## 连带发现（写入编码范式）

序守卫 wrapper 的嵌套 lambda 中直接调用外层 `form.getXxx()` 触发 javac 推断异常（同症状）；改为方法入口
先取局部变量再入 lambda 即通过——该写法已固化为序守卫实现范式。

## 教训

1. 任何新 Java 工程第一步：显式 UTF-8 编码声明（不要赌平台编码）；
2. "只有一个 getter 找不到"优先怀疑源码解析问题（编码/注释吞并），而不是 Lombok 版本。
