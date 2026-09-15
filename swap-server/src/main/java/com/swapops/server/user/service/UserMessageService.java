package com.swapops.server.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.common.RRException;
import com.swapops.server.user.dao.UserMessageDao;
import com.swapops.server.user.entity.UserMessageEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 站内信（S7 WP-D）：券发放/欠费产生/退款到账/报障工单关闭四类触达；
 * 写入失败只告警不影响业务（与审计同纪律）。
 */
@Slf4j
@Service
public class UserMessageService {

    private final UserMessageDao userMessageDao;

    public UserMessageService(UserMessageDao userMessageDao) {
        this.userMessageDao = userMessageDao;
    }

    public void send(Long userId, String type, String title, String content) {
        try {
            UserMessageEntity message = new UserMessageEntity();
            message.setUserId(userId);
            message.setType(type);
            message.setTitle(truncate(title, 128));
            message.setContent(truncate(content, 512));
            message.setReadFlag(0);
            message.setCreateTime(System.currentTimeMillis());
            userMessageDao.insert(message);
        } catch (Exception e) {
            log.warn("[站内信] 写入失败（不影响业务） userId={} type={} cause={}", userId, type, e.getMessage());
        }
    }

    public List<UserMessageEntity> list(Long userId, Boolean unreadOnly, Integer limit) {
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), 100);
        return userMessageDao.selectList(new LambdaQueryWrapper<UserMessageEntity>()
                .eq(UserMessageEntity::getUserId, userId)
                .eq(Boolean.TRUE.equals(unreadOnly), UserMessageEntity::getReadFlag, 0)
                .orderByDesc(UserMessageEntity::getId)
                .last("LIMIT " + size));
    }

    /** 标记已读（幂等：已读再次调用直接成功；他人消息拒绝） */
    public boolean markRead(Long userId, Long messageId) {
        int rows = userMessageDao.update(null, new LambdaUpdateWrapper<UserMessageEntity>()
                .eq(UserMessageEntity::getId, messageId)
                .eq(UserMessageEntity::getUserId, userId)
                .eq(UserMessageEntity::getReadFlag, 0)
                .set(UserMessageEntity::getReadFlag, 1));
        if (rows > 0) {
            return true;
        }
        UserMessageEntity existing = userMessageDao.selectOne(new LambdaQueryWrapper<UserMessageEntity>()
                .eq(UserMessageEntity::getId, messageId)
                .eq(UserMessageEntity::getUserId, userId));
        if (existing == null) {
            throw new RRException("消息不存在或无权访问: " + messageId);
        }
        return true;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
