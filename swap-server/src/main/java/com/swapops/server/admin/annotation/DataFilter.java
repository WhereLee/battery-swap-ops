package com.swapops.server.admin.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据范围标记（P1-8，参照 inteink-faster `common/annotation/DataFilter` 模式）：
 * 标注在"受数据范围约束"的管理端查询端点上。数据范围的真正生效在查询构建处
 * （`DataScopeSupport.apply*`），本注解用于：
 * <ul>
 *   <li>声明式标识（哪些端点是范围敏感的，供评审/测试扫描）；</li>
 *   <li>配合 {@code DataFilterAspect} 做"防空转自检"——STATION 范围身份进入已标注端点后
 *       若本次请求从未调用 apply，请求结束时记 warn（防未来新增查询漏接）。</li>
 * </ul>
 * 纪律：标注端点的查询路径必须调用 DataScopeSupport 的 apply/require 方法。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataFilter {

    /** 说明性标签（自检日志用，如 "cabinet-list"；缺省取 类名.方法名） */
    String value() default "";
}
