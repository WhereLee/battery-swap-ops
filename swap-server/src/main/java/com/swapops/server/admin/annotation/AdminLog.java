package com.swapops.server.admin.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理操作审计注解（S7 WP-A，参照壳子 @SysLog 模式）：标注在 /admin/** 写端点上，
 * AOP 落 admin_op_log（谁/何时/何动作/入参脱敏/结果/耗时/IP）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdminLog {

    /** 动作名（大写下划线，如 REFUND_CREATE / SETTLEMENT_PAY） */
    String value();
}
