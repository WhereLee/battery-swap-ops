package com.swapops.server.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.swapops.server.admin.dao.AdminOpLogDao;
import com.swapops.server.admin.dao.AdminUserDao;
import com.swapops.server.admin.entity.AdminOpLogEntity;
import com.swapops.server.admin.entity.AdminUserEntity;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminSecrets;
import com.swapops.server.common.RRException;
import com.swapops.server.common.utils.PageParams;
import com.swapops.server.common.utils.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理员账号管理（S7 WP-A）：创建/查询/启停；密码 BCrypt 存储，接口不回传密码哈希。
 */
@Slf4j
@Service
public class AdminAccountService {

    private static final String USERNAME_PATTERN = "^[a-zA-Z0-9_-]{3,32}$";
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AdminUserDao adminUserDao;
    private final AdminOpLogDao adminOpLogDao;

    public AdminAccountService(AdminUserDao adminUserDao, AdminOpLogDao adminOpLogDao) {
        this.adminUserDao = adminUserDao;
        this.adminOpLogDao = adminOpLogDao;
    }

    public AdminUserEntity create(String username, String password, String realName, String role) {
        String name = username == null ? "" : username.trim();
        if (!name.matches(USERNAME_PATTERN)) {
            throw new RRException("用户名格式非法（3~32 位字母/数字/_/-）: " + username);
        }
        AdminRole adminRole = AdminRole.fromCode(role);
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new RRException("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (adminUserDao.selectOne(new LambdaQueryWrapper<AdminUserEntity>()
                .eq(AdminUserEntity::getUsername, name)) != null) {
            throw new RRException("用户名已存在: " + name);
        }
        long now = System.currentTimeMillis();
        AdminUserEntity user = new AdminUserEntity();
        user.setUsername(name);
        user.setPasswordHash(AdminSecrets.hashPassword(password));
        user.setRealName(realName == null || realName.isBlank() ? null : realName.trim());
        user.setRole(adminRole.name());
        user.setStatus(1);
        user.setCreateTime(now);
        user.setUpdateTime(now);
        adminUserDao.insert(user);
        log.info("[admin] 管理员创建 id={} username={} role={}", user.getId(), name, adminRole);
        return mask(user);
    }

    public List<AdminUserEntity> list() {
        return adminUserDao.selectList(new LambdaQueryWrapper<AdminUserEntity>()
                        .orderByAsc(AdminUserEntity::getId))
                .stream().map(this::mask).toList();
    }

    /** 启停用：1 启用 / 2 停用（停用即时生效——resolve 实时校验状态） */
    public AdminUserEntity changeStatus(Long id, Integer status) {
        if (status == null || (status != 1 && status != 2)) {
            throw new RRException("管理员状态可选 1 启用 / 2 停用");
        }
        AdminUserEntity user = adminUserDao.selectById(id);
        if (user == null) {
            throw new RRException("管理员不存在: " + id);
        }
        adminUserDao.update(null, new LambdaUpdateWrapper<AdminUserEntity>()
                .eq(AdminUserEntity::getId, id)
                .set(AdminUserEntity::getStatus, status)
                .set(AdminUserEntity::getUpdateTime, System.currentTimeMillis()));
        log.warn("[admin] 管理员状态变更 id={} username={} status={}", id, user.getUsername(), status);
        return mask(adminUserDao.selectById(id));
    }

    /** 审计日志分页（管理端可查） */
    public PageResult<AdminOpLogEntity> pageLogs(Integer page, Integer limit, Long adminId, String action) {
        IPage<AdminOpLogEntity> result = adminOpLogDao.selectPage(
                new Page<>(PageParams.page(page), PageParams.limit(limit)),
                new LambdaQueryWrapper<AdminOpLogEntity>()
                        .eq(adminId != null, AdminOpLogEntity::getAdminId, adminId)
                        .eq(action != null && !action.isBlank(), AdminOpLogEntity::getAction, action)
                        .orderByDesc(AdminOpLogEntity::getId));
        return PageResult.of(result);
    }

    private AdminUserEntity mask(AdminUserEntity user) {
        if (user != null) {
            user.setPasswordHash(null);
        }
        return user;
    }
}
