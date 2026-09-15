package com.swapops.server.admin.aspect;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.dao.AdminOpLogDao;
import com.swapops.server.admin.entity.AdminOpLogEntity;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计切面单测（S7 WP-A）：入参脱敏（password/token 不落库）、失败留痕并原样抛错。
 */
@DisplayName("管理操作审计切面")
@ExtendWith(MockitoExtension.class)
class AdminLogAspectTest {

    @Mock
    private AdminOpLogDao adminOpLogDao;

    private AdminLogAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new AdminLogAspect(adminOpLogDao);
    }

    private ProceedingJoinPoint point(Object[] args, Object result, Throwable toThrow) throws Throwable {
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.toShortString()).thenReturn("AdminController.method()");
        when(point.getSignature()).thenReturn(signature);
        when(point.getArgs()).thenReturn(args);
        if (toThrow != null) {
            when(point.proceed()).thenThrow(toThrow);
        } else {
            when(point.proceed()).thenReturn(result);
        }
        return point;
    }

    private AdminLog annotation(String value) {
        return new AdminLog() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return AdminLog.class;
            }

            @Override
            public String value() {
                return value;
            }
        };
    }

    @Test
    @DisplayName("成功路径：落库且敏感字段脱敏")
    void 成功落库并脱敏() throws Throwable {
        ProceedingJoinPoint point = point(
                new Object[]{Map.of("username", "ops1", "password", "Secret#123")}, "ok", null);

        Object result = aspect.around(point, annotation("ADMIN_CREATE"));

        assertThat(result).isEqualTo("ok");
        ArgumentCaptor<AdminOpLogEntity> captor = ArgumentCaptor.forClass(AdminOpLogEntity.class);
        verify(adminOpLogDao).insert(captor.capture());
        AdminOpLogEntity log = captor.getValue();
        assertThat(log.getAction()).isEqualTo("ADMIN_CREATE");
        assertThat(log.getResultCode()).isZero();
        assertThat(log.getParamsJson()).contains("ops1");
        assertThat(log.getParamsJson()).doesNotContain("Secret#123");
        assertThat(log.getParamsJson()).contains("***");
    }

    @Test
    @DisplayName("失败路径：留痕 resultCode=1 + 截断错误，异常原样抛出")
    void 失败留痕并抛错() throws Throwable {
        ProceedingJoinPoint point = point(new Object[]{"arg"}, null,
                new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.around(point, annotation("PLAN_DELETE")))
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");

        ArgumentCaptor<AdminOpLogEntity> captor = ArgumentCaptor.forClass(AdminOpLogEntity.class);
        verify(adminOpLogDao).insert(captor.capture());
        assertThat(captor.getValue().getResultCode()).isEqualTo(1);
        assertThat(captor.getValue().getError()).contains("boom");
    }
}
