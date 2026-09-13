package com.swapops.server.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.swapops.server.common.RRException;
import com.swapops.server.common.utils.PageParams;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.device.config.SwapRedisKeys;
import com.swapops.server.user.dao.SwapUserDao;
import com.swapops.server.user.entity.SwapUserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 用户账户会话：手机号登录 → 不透明 token（Redis，TTL 7 天，服务端可吊销）。
 * P0 无短信/微信授权（联调形态，见 README）；会话有效性同时校验账号状态。
 */
@Slf4j
@Service
public class UserAccountService {

    static final Duration TOKEN_TTL = Duration.ofDays(7);

    private final SwapUserDao userDao;
    private final StringRedisTemplate stringRedisTemplate;

    public UserAccountService(SwapUserDao userDao, StringRedisTemplate stringRedisTemplate) {
        this.userDao = userDao;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 登录：签发 token（覆盖式写入，不做同端互斥——样例边界按用户多端并存处理） */
    public String login(String phone) {
        SwapUserEntity user = userDao.selectOne(new LambdaQueryWrapper<SwapUserEntity>()
                .eq(SwapUserEntity::getPhone, phone));
        if (user == null) {
            throw new RRException("用户不存在: " + phone);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new RRException("账号不可用: " + phone);
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(SwapRedisKeys.USER_TOKEN_PREFIX + token,
                String.valueOf(user.getId()), TOKEN_TTL);
        log.info("用户登录 userId={} phone={}", user.getId(), phone);
        return token;
    }

    /** 解析 token → userId（无效/过期/账号异常返回 null） */
    public Long resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String value = stringRedisTemplate.opsForValue().get(SwapRedisKeys.USER_TOKEN_PREFIX + token);
        if (value == null) {
            return null;
        }
        Long userId;
        try {
            userId = Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
        SwapUserEntity user = userDao.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            return null;
        }
        return userId;
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            stringRedisTemplate.delete(SwapRedisKeys.USER_TOKEN_PREFIX + token);
        }
    }

    public SwapUserEntity requireActive(Long userId) {
        SwapUserEntity user = userDao.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new RRException(401, "账号不可用");
        }
        return user;
    }

    public SwapUserEntity findByPhone(String phone) {
        return userDao.selectOne(new LambdaQueryWrapper<SwapUserEntity>()
                .eq(SwapUserEntity::getPhone, phone));
    }

    // ---------- 管理端（S4.5 批二） ----------

    /** 用户分页（phone 模糊；管理端直读 DB） */
    public PageResult<SwapUserEntity> adminPage(Integer page, Integer limit, String phone) {
        IPage<SwapUserEntity> result = userDao.selectPage(
                new Page<>(PageParams.page(page), PageParams.limit(limit)),
                new LambdaQueryWrapper<SwapUserEntity>()
                        .like(phone != null && !phone.isBlank(), SwapUserEntity::getPhone, phone)
                        .orderByAsc(SwapUserEntity::getId));
        return PageResult.of(result);
    }

    /**
     * 启停用：1 正常 / 2 停用。
     * 停用**即时生效**：login / resolve / requireActive 均实时校验 status，无需清 token（已签发 token 下次请求即被拒）。
     */
    public SwapUserEntity changeStatus(Long userId, Integer status) {
        if (status == null || (status != 1 && status != 2)) {
            throw new RRException("用户状态可选 1 正常 / 2 停用");
        }
        SwapUserEntity user = userDao.selectById(userId);
        if (user == null) {
            throw new RRException("用户不存在: " + userId);
        }
        user.setStatus(status);
        user.setUpdateTime(System.currentTimeMillis());
        userDao.updateById(user);
        log.warn("用户状态变更 userId={} phone={} status={}", userId, user.getPhone(), status);
        return userDao.selectById(userId);
    }
}
