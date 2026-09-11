# 坑位：单元测试全绿但 Spring 装配失败的两种固定形态（S3.8 连踩两次）

**背景**：S3.8 两次本地启动失败，均为单测无法覆盖的"装配期"缺陷。记录模板，后续新组件提交前自查。

## 形态 1：新 dao 包未纳入 @MapperScan

- 现象：启动报 `required a bean of type '...AlarmDao' that could not be found`。
- 根因：`@MapperScan` 是按包列出的（device/user/order/asset），新增 `alarm.dao` 未登记。
- 自查项：**新增 `XxxDao` 时，先确认 `SwapServerApplication` 的扫描列表包含该包**。

## 形态 2：多构造器未标注注入构造（连续两次：MqEventReporter、SnowflakeIdGenerator）

- 现象：`No default constructor found`（Spring 找不到无参构造，也不会自动挑参数最多的）。
- 根因：为了测试接缝开了第二个构造器（`(props, Producer)` / `(dc, worker, tolerance, clock)`），
  Spring 不再推断，需要在"装配用构造器"上显式 `@Autowired`。
- 自查项：**凡是"Spring 构造器 + 测试接缝构造器"并存的类，Spring 侧构造器必须 @Autowired**。

## 为什么单测挡不住

- 单测直接 `new`，不走容器；构造器选择、包扫描、Bean 循环依赖都只在容器启动时暴露。
- 现有 CI 只跑 `mvn test`（无 @SpringBootTest），所以**每次新增组件后必须本地起一次服务**，
  或补一个轻量 `@SpringBootTest` 上下文冒烟测试（roadmap：S4 前补 "context loads" 测试，成本低）。

## 处置记录

- 形态 1：`AlarmDao` 加入 `@MapperScan`（fix: 启动装配修复，commit `4c00cbc`）。
- 形态 2a：`MqEventReporter` 公开构造器加 `@Autowired`（同上）。
- 形态 2b：`SnowflakeIdGenerator` 装配构造器加 `@Autowired`（WP5，commit `cc41f15`）。
