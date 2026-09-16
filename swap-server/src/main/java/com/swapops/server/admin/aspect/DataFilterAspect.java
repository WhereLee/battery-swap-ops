package com.swapops.server.admin.aspect;

import com.swapops.server.admin.annotation.DataFilter;
import com.swapops.server.admin.data.DataScopeGuard;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * 数据范围自检切面（P1-8，模式参照 inteink-faster `DataFilterAspect` 的注解+切面骨架）：
 * 进入 @DataFilter 端点时建立自检标记，退出时若"受限身份但查询从未应用范围过滤"记 warn。
 * 与参照实现不同：本切面不拼接 SQL、不写参数对象——过滤的真正注入在查询构建处
 * （DataScopeSupport.apply*，类型安全的 LambdaQueryWrapper 条件），切面只做旁路自检。
 */
@Slf4j
@Aspect
@Component
public class DataFilterAspect {

    private static final String POINTCUT = "@annotation(com.swapops.server.admin.annotation.DataFilter)";

    @Before(POINTCUT)
    public void enter(JoinPoint point) {
        DataScopeGuard.enter(label(point));
    }

    @AfterReturning(POINTCUT)
    public void exit(JoinPoint point) {
        check("return");
    }

    @AfterThrowing(pointcut = POINTCUT, throwing = "e")
    public void exitOnError(JoinPoint point, Throwable e) {
        check("error");
    }

    private void check(String phase) {
        String missed = DataScopeGuard.exitAndCheck();
        if (missed != null) {
            log.warn("[data-scope] 受限身份进入已标注端点但未应用范围过滤（自检） api={} phase={}", missed, phase);
        }
    }

    private String label(JoinPoint point) {
        MethodSignature signature = (MethodSignature) point.getSignature();
        DataFilter dataFilter = signature.getMethod().getAnnotation(DataFilter.class);
        if (dataFilter != null && !dataFilter.value().isEmpty()) {
            return dataFilter.value();
        }
        return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
    }
}
