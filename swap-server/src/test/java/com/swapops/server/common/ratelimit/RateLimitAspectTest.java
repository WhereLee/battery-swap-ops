package com.swapops.server.common.ratelimit;

import com.swapops.server.common.web.UserContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流切面单测（S3.8 WP2）：放行/拒绝/fail-open/维度键/SpEL/总开关。
 */
@DisplayName("令牌桶限流切面")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitAspectTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ProceedingJoinPoint pjp;
    @Mock
    private MethodSignature signature;

    private RateLimitProperties properties;
    private RateLimitAspect aspect;

    static class Fixture {
        @RateLimit(name = "global-l", dimension = RateLimitDimension.GLOBAL, permits = 5, windowSeconds = 5)
        public void global() {
        }

        @RateLimit(name = "user-l", dimension = RateLimitDimension.USER, permits = 5, windowSeconds = 60)
        public void user() {
        }

        @RateLimit(name = "api-l", dimension = RateLimitDimension.API, permits = 1, windowSeconds = 1)
        public void api() {
        }

        @RateLimit(name = "spel-l", dimension = RateLimitDimension.USER, permits = 1, windowSeconds = 1, key = "#p0")
        public void withKey(String phone) {
        }

        /** 登录撞库防护的真实形状（AdminAuthController#login） */
        @RateLimit(name = "admin-login", dimension = RateLimitDimension.IP, permits = 5, windowSeconds = 5)
        public void loginByIp() {
        }
    }

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        aspect = new RateLimitAspect(redis, properties, new ClientIpResolver(properties));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private RateLimit annotation(String method, Class<?>... parameterTypes) throws Exception {
        return Fixture.class.getMethod(method, parameterTypes).getAnnotation(RateLimit.class);
    }

    private java.lang.reflect.Method resolve(String method) {
        return java.util.Arrays.stream(Fixture.class.getMethods())
                .filter(m -> m.getName().equals(method))
                .findFirst()
                .orElseThrow();
    }

    private void givenMethod(String method, Object... args) throws Exception {
        when(pjp.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(resolve(method));
        when(signature.toShortString()).thenReturn("Fixture." + method + "()");
        when(pjp.getArgs()).thenReturn(args);
    }

    @SuppressWarnings("unchecked")
    private void givenAcquire(long allowed) {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(List.of(allowed, 0L));
    }

    @Test
    @DisplayName("放行：扣令牌成功则执行方法体")
    void 放行() throws Throwable {
        UserContext.set(7L);
        givenMethod("user");
        givenAcquire(1L);

        aspect.around(pjp, annotation("user"));

        verify(pjp).proceed();
    }

    @Test
    @DisplayName("拒绝：无令牌抛 429 异常且不执行方法体（Retry-After=window/permits）")
    void 拒绝() throws Throwable {
        UserContext.set(7L);
        givenMethod("user");
        givenAcquire(0L);

        assertThatThrownBy(() -> aspect.around(pjp, annotation("user")))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfterSeconds())
                        .isEqualTo(12L));
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("Redis 故障：fail-open 放行")
    void redis故障放行() throws Throwable {
        givenMethod("global");
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));

        aspect.around(pjp, annotation("global"));

        verify(pjp).proceed();
    }

    @Test
    @DisplayName("总开关关闭：直接放行，不触达 Redis")
    void 开关关闭() throws Throwable {
        properties.setEnabled(false);
        givenMethod("global");

        aspect.around(pjp, annotation("global"));

        verify(pjp).proceed();
    }

    @Test
    @DisplayName("SpEL 附加键：参数值进入桶键")
    void spel附加键() throws Throwable {
        UserContext.set(7L);
        givenMethod("withKey", "13900000009");
        givenAcquire(1L);

        aspect.around(pjp, annotation("withKey", String.class));

        org.mockito.ArgumentCaptor<List> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), captor.capture(), any(), any());
        assertThat(captor.getValue().get(0).toString()).contains(":7:13900000009");
    }

    @Test
    @DisplayName("API 维度：方法签名进入桶键")
    void api维度() throws Throwable {
        givenMethod("api");
        givenAcquire(1L);

        aspect.around(pjp, annotation("api"));

        org.mockito.ArgumentCaptor<List> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), captor.capture(), any(), any());
        assertThat(captor.getValue().get(0).toString()).contains("api-l").contains("Fixture.api()");
    }

    @Test
    @DisplayName("IP 维度（批次33）：伪造 X-Forwarded-For 不改变桶键（撞库防护不可被 header 绕过）")
    void ip维度忽略伪造XFF() throws Throwable {
        givenMethod("loginByIp");
        givenAcquire(1L);

        java.util.Set<String> buckets = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            org.springframework.mock.web.MockHttpServletRequest request =
                    new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/admin/auth/login");
            request.setRemoteAddr("203.0.113.7");
            request.addHeader("X-Forwarded-For", "10.0.0." + i);
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(request));

            org.mockito.ArgumentCaptor<List> captor = org.mockito.ArgumentCaptor.forClass(List.class);
            aspect.around(pjp, annotation("loginByIp"));
            verify(redis, org.mockito.Mockito.atLeastOnce())
                    .execute(any(RedisScript.class), captor.capture(), any(), any());
            buckets.add(captor.getValue().get(0).toString());
            org.mockito.Mockito.clearInvocations(redis);
        }
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();

        assertThat(buckets)
                .as("20 个不同 XFF 必须落进同一个桶：%s", buckets)
                .hasSize(1);
        assertThat(buckets.iterator().next()).contains("203.0.113.7").doesNotContain("10.0.0.");
    }
}
