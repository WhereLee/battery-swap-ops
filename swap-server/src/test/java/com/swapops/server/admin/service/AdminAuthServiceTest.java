package com.swapops.server.admin.service;

import com.swapops.server.admin.dao.AdminOpLogDao;
import com.swapops.server.admin.dao.AdminUserDao;
import com.swapops.server.admin.entity.AdminUserEntity;
import com.swapops.server.admin.security.AdminSecrets;
import com.swapops.server.common.RRException;
import com.swapops.server.device.config.SwapRedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端认证单测（S7 WP-A）：登录（成功/密码错/停用）、会话解析、登出吊销、登录审计。
 */
@DisplayName("管理端认证")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAuthServiceTest {

    @Mock
    private AdminUserDao adminUserDao;
    @Mock
    private AdminOpLogDao adminOpLogDao;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private AdminAuthService service;

    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusLambdaCache() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                assistant, com.swapops.server.admin.entity.AdminUserEntity.class);
    }

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new AdminAuthService(adminUserDao, adminOpLogDao, stringRedisTemplate, 12);
    }

    private AdminUserEntity user(int status, String rawPassword) {
        AdminUserEntity user = new AdminUserEntity();
        user.setId(5L);
        user.setUsername("ops1");
        user.setRole("OPS");
        user.setStatus(status);
        user.setPasswordHash(AdminSecrets.hashPassword(rawPassword));
        return user;
    }

    @Test
    @DisplayName("登录成功：签发 32hex 会话并更新最后登录时间 + 审计")
    void 登录成功() {
        when(adminUserDao.selectOne(any())).thenReturn(user(1, "Ops#12345"));

        String token = service.login("ops1", "Ops#12345");

        assertThat(token).matches("^[0-9a-f]{32}$");
        verify(valueOperations).set(eq(SwapRedisKeys.ADMIN_TOKEN_PREFIX + token), eq("5"), any());
        verify(adminUserDao).update(isNull(), any());
        verify(adminOpLogDao).insert(any(com.swapops.server.admin.entity.AdminOpLogEntity.class));
    }

    @Test
    @DisplayName("密码错误 / 账号停用：统一业务异常 + 失败审计")
    void 登录失败() {
        when(adminUserDao.selectOne(any())).thenReturn(user(1, "Ops#12345"));
        assertThatThrownBy(() -> service.login("ops1", "bad-pass"))
                .isInstanceOf(RRException.class).hasMessageContaining("账号或密码错误");

        when(adminUserDao.selectOne(any())).thenReturn(user(2, "Ops#12345"));
        assertThatThrownBy(() -> service.login("ops1", "Ops#12345"))
                .isInstanceOf(RRException.class).hasMessageContaining("账号或密码错误");

        verify(valueOperations, never()).set(anyString(), anyString(), any());
        verify(adminOpLogDao, org.mockito.Mockito.times(2))
                .insert(any(com.swapops.server.admin.entity.AdminOpLogEntity.class));
    }

    @Test
    @DisplayName("会话解析：有效返回用户；无效/停用返回 null；登出删除键")
    void 会话解析与登出() {
        when(valueOperations.get(SwapRedisKeys.ADMIN_TOKEN_PREFIX + "t1")).thenReturn("5");
        when(adminUserDao.selectById(5L)).thenReturn(user(1, "x"));
        assertThat(service.resolve("t1")).isNotNull();

        when(adminUserDao.selectById(5L)).thenReturn(user(2, "x"));
        assertThat(service.resolve("t1")).isNull();

        when(valueOperations.get(SwapRedisKeys.ADMIN_TOKEN_PREFIX + "t2")).thenReturn(null);
        assertThat(service.resolve("t2")).isNull();
        assertThat(service.resolve(null)).isNull();

        service.logout("t1");
        verify(stringRedisTemplate).delete(SwapRedisKeys.ADMIN_TOKEN_PREFIX + "t1");
    }

    private static <T> T isNull() {
        return org.mockito.ArgumentMatchers.isNull();
    }
}
