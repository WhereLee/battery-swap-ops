package com.swapops.server.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.admin.dao.AdminOpLogDao;
import com.swapops.server.admin.dao.AdminUserDao;
import com.swapops.server.admin.entity.AdminOpLogEntity;
import com.swapops.server.admin.entity.AdminUserEntity;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminSecrets;
import com.swapops.server.common.RRException;
import com.swapops.server.device.config.SwapRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * 管理端认证（S7 WP-A）：账号+密码（BCrypt）→ 会话 token（Redis，TTL 可配，服务端可吊销）。
 * 登录/登出双事件写 admin_op_log（登录动作先于鉴权链，审计在服务内直写，密码不出现在日志）。
 */
@Slf4j
@Service
public class AdminAuthService {

    private final AdminUserDao adminUserDao;
    private final AdminOpLogDao adminOpLogDao;
    private final StringRedisTemplate stringRedisTemplate;
    private final Duration sessionTtl;

    public AdminAuthService(AdminUserDao adminUserDao, AdminOpLogDao adminOpLogDao,
                            StringRedisTemplate stringRedisTemplate,
                            @Value("${swap.admin.session-ttl-hours:12}") long sessionTtlHours) {
        this.adminUserDao = adminUserDao;
        this.adminOpLogDao = adminOpLogDao;
        this.stringRedisTemplate = stringRedisTemplate;
        this.sessionTtl = Duration.ofHours(sessionTtlHours);
    }

    /** 登录：校验通过签发会话 token（覆盖式，允许多端并存） */
    public String login(String username, String password) {
        AdminUserEntity user = findByUsername(username);
        boolean ok = user != null && user.getStatus() != null && user.getStatus() == 1
                && AdminSecrets.matches(password, user.getPasswordHash());
        if (!ok) {
            auditLogin(user == null ? null : user.getId(), username, false, "账号或密码错误");
            throw new RRException("账号或密码错误");
        }
        String token = AdminSecrets.generateToken();
        stringRedisTemplate.opsForValue().set(SwapRedisKeys.ADMIN_TOKEN_PREFIX + token,
                String.valueOf(user.getId()), sessionTtl);
        adminUserDao.update(null, new LambdaUpdateWrapper<AdminUserEntity>()
                .eq(AdminUserEntity::getId, user.getId())
                .set(AdminUserEntity::getLastLoginTime, System.currentTimeMillis())
                .set(AdminUserEntity::getUpdateTime, System.currentTimeMillis()));
        auditLogin(user.getId(), user.getUsername(), true, null);
        log.info("[admin] 登录成功 userId={} username={} role={}", user.getId(), user.getUsername(), user.getRole());
        return token;
    }

    /** 登出：吊销会话 */
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            stringRedisTemplate.delete(SwapRedisKeys.ADMIN_TOKEN_PREFIX + token);
        }
    }

    /** 会话解析：token → 管理员（无效/过期/停用返回 null） */
    public AdminUserEntity resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String value = stringRedisTemplate.opsForValue().get(SwapRedisKeys.ADMIN_TOKEN_PREFIX + token);
        if (value == null) {
            return null;
        }
        Long userId;
        try {
            userId = Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
        AdminUserEntity user = adminUserDao.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            return null;
        }
        return user;
    }

    /** 角色 → 权限码集合 */
    public Set<String> permissions(AdminUserEntity user) {
        return AdminRole.fromCode(user.getRole()).permissions();
    }

    public AdminUserEntity findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return adminUserDao.selectOne(new LambdaQueryWrapper<AdminUserEntity>()
                .eq(AdminUserEntity::getUsername, username.trim()));
    }

    private void auditLogin(Long adminId, String username, boolean success, String error) {
        try {
            AdminOpLogEntity log = new AdminOpLogEntity();
            log.setAdminId(adminId);
            log.setUsername(username == null ? "unknown" : username);
            log.setAction("LOGIN");
            log.setMethod("POST");
            log.setUri("/admin/auth/login");
            log.setParamsJson(null);
            log.setResultCode(success ? 0 : 1);
            log.setError(error);
            log.setDurationMs(0L);
            log.setCreateTime(System.currentTimeMillis());
            adminOpLogDao.insert(log);
        } catch (Exception e) {
            log.warn("[admin] 登录审计写入失败（不影响登录）: {}", e.getMessage());
        }
    }
}
