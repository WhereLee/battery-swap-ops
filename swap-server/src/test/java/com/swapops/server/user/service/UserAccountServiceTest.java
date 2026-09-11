package com.swapops.server.user.service;

import com.swapops.server.common.RRException;
import com.swapops.server.user.dao.SwapUserDao;
import com.swapops.server.user.entity.SwapUserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户会话单测：登录签发不透明 token / 解析（含账号异常）/ 登出吊销。
 */
@DisplayName("用户账户会话")
@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    @Mock
    private SwapUserDao userDao;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @InjectMocks
    private UserAccountService service;

    private SwapUserEntity user(long id, int status) {
        SwapUserEntity user = new SwapUserEntity();
        user.setId(id);
        user.setPhone("13800000001");
        user.setStatus(status);
        return user;
    }

    @Test
    @DisplayName("登录：签发 32 位 token 并写 Redis（TTL 7 天）")
    void 登录签发token() {
        when(userDao.selectOne(any())).thenReturn(user(7L, 1));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        String token = service.login("13800000001");

        assertThat(token).hasSize(32);
        verify(valueOperations).set(startsWith("swap:user-token:"), eq("7"), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("登录：用户不存在 / 账号冻结均拒绝")
    void 登录拒绝分支() {
        when(userDao.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.login("13800000009"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("用户不存在");

        when(userDao.selectOne(any())).thenReturn(user(7L, 2));
        assertThatThrownBy(() -> service.login("13800000001"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("账号不可用");
    }

    @Test
    @DisplayName("resolve：有效 token → userId；无效/账号异常 → null")
    void resolve分支() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("7");
        when(userDao.selectById(7L)).thenReturn(user(7L, 1));
        assertThat(service.resolve("tok")).isEqualTo(7L);

        when(valueOperations.get(anyString())).thenReturn(null);
        assertThat(service.resolve("tok")).isNull();
        assertThat(service.resolve(null)).isNull();

        when(valueOperations.get(anyString())).thenReturn("7");
        when(userDao.selectById(7L)).thenReturn(user(7L, 2));
        assertThat(service.resolve("tok")).isNull();
    }

    @Test
    @DisplayName("登出：删除 token 键")
    void 登出吊销() {
        service.logout("tok");
        verify(stringRedisTemplate).delete("swap:user-token:tok");
    }
}
