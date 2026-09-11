package com.swapops.server.outbox.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.outbox.dao.OutboxEventDao;
import com.swapops.server.outbox.entity.OutboxEventEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * outbox 仓储（S3.8 WP6）：入队=业务事务内插入（event_key 唯一=幂等）；
 * 中继按状态推进 NEW→SENT / NEW→(重试)→DEAD；所有更新带状态条件（CAS），防并发中继重复推进。
 */
@Slf4j
@Service
public class OutboxService {

    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_DEAD = "DEAD";

    private final OutboxEventDao outboxEventDao;

    public OutboxService(OutboxEventDao outboxEventDao) {
        this.outboxEventDao = outboxEventDao;
    }

    /**
     * 入队（在业务事务内调用，与业务写同提交/回滚）。
     *
     * @return true=新入队；false=幂等键已存在（重复事件，忽略）
     */
    public boolean enqueue(String eventKey, String eventType, String payload, String traceId) {
        long now = System.currentTimeMillis();
        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventKey(eventKey);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setStatus(STATUS_NEW);
        event.setAttempts(0);
        event.setNextRetryTime(now);
        event.setTraceId(traceId);
        event.setCreateTime(now);
        event.setUpdateTime(now);
        try {
            outboxEventDao.insert(event);
            log.info("[outbox] 入队 key={} type={}", eventKey, eventType);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("[outbox] 幂等命中，忽略重复入队 key={}", eventKey);
            return false;
        }
    }

    /** 到期未发送的事件（按时间序，限量） */
    public List<OutboxEventEntity> findDue(long now, int limit) {
        return outboxEventDao.selectList(new LambdaQueryWrapper<OutboxEventEntity>()
                .eq(OutboxEventEntity::getStatus, STATUS_NEW)
                .le(OutboxEventEntity::getNextRetryTime, now)
                .orderByAsc(OutboxEventEntity::getId)
                .last("LIMIT " + limit));
    }

    public boolean markSent(Long id) {
        return updateStatus(id, STATUS_SENT, wrapper -> wrapper
                .set(OutboxEventEntity::getSentTime, System.currentTimeMillis())
                .set(OutboxEventEntity::getLastError, null));
    }

    /** 重试：attempts+1，退避后重排（仅 NEW 可推进） */
    public boolean markRetry(Long id, int attempts, long nextRetryTime, String error) {
        return updateStatus(id, STATUS_NEW, wrapper -> wrapper
                .set(OutboxEventEntity::getAttempts, attempts)
                .set(OutboxEventEntity::getNextRetryTime, nextRetryTime)
                .set(OutboxEventEntity::getLastError, truncate(error)));
    }

    /** 死信：重试耗尽/无发布器（仅 NEW 可推进） */
    public boolean markDead(Long id, String error) {
        return updateStatus(id, STATUS_DEAD, wrapper -> wrapper
                .set(OutboxEventEntity::getLastError, truncate(error)));
    }

    private boolean updateStatus(Long id, String newStatus,
                                 java.util.function.Consumer<LambdaUpdateWrapper<OutboxEventEntity>> extra) {
        LambdaUpdateWrapper<OutboxEventEntity> wrapper = new LambdaUpdateWrapper<OutboxEventEntity>()
                .eq(OutboxEventEntity::getId, id)
                .eq(OutboxEventEntity::getStatus, STATUS_NEW)
                .set(OutboxEventEntity::getStatus, newStatus)
                .set(OutboxEventEntity::getUpdateTime, System.currentTimeMillis());
        extra.accept(wrapper);
        return outboxEventDao.update(null, wrapper) > 0;
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 255 ? error : error.substring(0, 255);
    }
}
