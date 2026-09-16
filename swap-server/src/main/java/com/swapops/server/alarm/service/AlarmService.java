package com.swapops.server.alarm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.alarm.config.AlarmProperties;
import com.swapops.server.alarm.dao.AlarmDao;
import com.swapops.server.alarm.entity.AlarmEntity;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.common.filter.TraceIdFilter;
import com.swapops.server.device.config.SwapRedisKeys;
import com.swapops.server.outbox.service.AlarmOutboxPublisher;
import com.swapops.server.outbox.service.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 告警治理（S3.6）：
 * <ul>
 *   <li>去重：SETNX(type:deviceNo) 窗口 + DB 时间窗兜底（Redis 故障/丢失时防重复入库）；</li>
 *   <li>限速：按类型 ZSET 滑动窗口计数，超限只日志（防告警风暴）；</li>
 *   <li>恢复：事件到位/心跳恢复自动关（handled=1，handler 空=系统关）；</li>
 *   <li>人工处理：CAS 0→1 + 发布处理事件（审计）。</li>
 * </ul>
 * Redis 在治理路径 fail-open（告警宁可多不可漏），DB 为准。
 */
@Slf4j
@Service
public class AlarmService {

    public static final String DEVICE_CABINET = "CABINET";
    public static final String DEVICE_CELL = "CELL";
    public static final String DEVICE_ORDER = "ORDER";
    public static final String DEVICE_JOB = "JOB";
    public static final String DEVICE_SYSTEM = "SYSTEM";
    public static final String DEVICE_BATTERY = "BATTERY";

    private final AlarmDao alarmDao;
    private final StringRedisTemplate stringRedisTemplate;
    private final AlarmProperties properties;
    private final AlarmEventPublisher publisher;
    private final OutboxService outboxService;
    private final AlarmWebhookNotifier webhookNotifier;

    public AlarmService(AlarmDao alarmDao, StringRedisTemplate stringRedisTemplate,
                        AlarmProperties properties, AlarmEventPublisher publisher,
                        OutboxService outboxService, AlarmWebhookNotifier webhookNotifier) {
        this.alarmDao = alarmDao;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.publisher = publisher;
        this.outboxService = outboxService;
        this.webhookNotifier = webhookNotifier;
    }

    /**
     * 产生告警（幂等/限速）。
     *
     * @return 新告警 id；去重命中/限速丢弃返回 null
     */
    @Transactional
    public Long raise(String deviceType, String deviceNo, AlarmType type, String content) {
        String dedupKey = SwapRedisKeys.ALARM_DEDUP_PREFIX + type + ":" + deviceNo;
        boolean firstSeen = true;
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(dedupKey, "1", Duration.ofSeconds(properties.getDedupWindowSeconds()));
            firstSeen = Boolean.TRUE.equals(acquired);
        } catch (RuntimeException e) {
            log.warn("告警去重 Redis 异常（fail-open 继续判定） type={} deviceNo={} cause={}",
                    type, deviceNo, e.getMessage());
        }
        if (!firstSeen) {
            log.debug("告警去重窗口内重复，忽略 type={} deviceNo={}", type, deviceNo);
            return null;
        }
        if (rateLimited(type)) {
            log.warn("告警类型限速丢弃（只日志不入库） type={} deviceNo={} limit={}/min",
                    type, deviceNo, properties.getRateLimitPerMinute());
            return null;
        }
        // DB 时间窗兜底（Redis 被清/重启窗口）：同 key 未处理告警已存在则不重复入库
        AlarmEntity existing = alarmDao.selectOne(new LambdaQueryWrapper<AlarmEntity>()
                .eq(AlarmEntity::getDeviceType, deviceType)
                .eq(AlarmEntity::getDeviceNo, deviceNo)
                .eq(AlarmEntity::getAlarmType, type.name())
                .eq(AlarmEntity::getHandled, 0)
                .ge(AlarmEntity::getCreateTime,
                        System.currentTimeMillis() - properties.getDedupWindowSeconds() * 1000L)
                .orderByDesc(AlarmEntity::getId)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing.getId();
        }
        long now = System.currentTimeMillis();
        AlarmEntity alarm = new AlarmEntity();
        alarm.setDeviceType(deviceType);
        alarm.setDeviceNo(deviceNo);
        alarm.setAlarmType(type.name());
        alarm.setContent(content);
        alarm.setHandled(0);
        alarm.setCreateTime(now);
        alarmDao.insert(alarm);
        log.warn("告警产生 id={} type={} deviceType={} deviceNo={} content={}",
                alarm.getId(), type, deviceType, deviceNo, content);
        // S3.8 WP6：事件走 outbox（与告警同事务落库），中继补投——发布失败不再丢事件
        String raisedEnvelope = publisher.buildEnvelope(alarm, "RAISED");
        outboxService.enqueue("alarm:" + alarm.getId() + ":RAISED", AlarmOutboxPublisher.EVENT_TYPE_ALARM,
                raisedEnvelope, TraceIdFilter.currentOrGenerate());
        // P1-11：出站 webhook 通知（异步尽力而为；RAISED/HANDLED/RECOVERED 三类事件）
        webhookNotifier.notify("RAISED", raisedEnvelope);
        return alarm.getId();
    }

    /** 自动恢复（事件到位/心跳恢复）：关闭未处理的同 key 告警；返回关闭条数 */
    public int markRecovered(String deviceType, String deviceNo, AlarmType type) {
        clearDedup(type, deviceNo);
        // 先查未处理告警（update 前取内容，用于构造 RECOVERED 出站信封）；
        // CAS 语义仍以 update 的 rows 为准：并发人工处理抢先时本处 update=0 不通知
        List<AlarmEntity> openList = alarmDao.selectList(new LambdaQueryWrapper<AlarmEntity>()
                .eq(AlarmEntity::getDeviceType, deviceType)
                .eq(AlarmEntity::getDeviceNo, deviceNo)
                .eq(AlarmEntity::getAlarmType, type.name())
                .eq(AlarmEntity::getHandled, 0));
        long now = System.currentTimeMillis();
        int rows = alarmDao.update(null, new LambdaUpdateWrapper<AlarmEntity>()
                .eq(AlarmEntity::getDeviceType, deviceType)
                .eq(AlarmEntity::getDeviceNo, deviceNo)
                .eq(AlarmEntity::getAlarmType, type.name())
                .eq(AlarmEntity::getHandled, 0)
                .set(AlarmEntity::getHandled, 1)
                .set(AlarmEntity::getHandledTime, now));
        if (rows > 0) {
            log.info("告警自动恢复 type={} deviceNo={} rows={}", type, deviceNo, rows);
            // P1-11：恢复通知（自动关不走 outbox——审计事件仅 RAISED/HANDLED；webhook 侧仍出站 RECOVERED 闭环）
            for (AlarmEntity alarm : openList) {
                alarm.setHandled(1);
                alarm.setHandledTime(now);
                webhookNotifier.notify("RECOVERED", publisher.buildEnvelope(alarm, "RECOVERED"));
            }
        }
        return rows;
    }

    /** 心跳恢复：关闭该柜离线告警 */
    public void markOnlineRecovered(String cabinetNo) {
        markRecovered(DEVICE_CABINET, cabinetNo, AlarmType.OFFLINE);
    }

    /** 人工处理（管理端）：CAS 0→1；成功后处理事件走 outbox（审计，可补投） */
    @Transactional
    public boolean handle(Long alarmId, Long userId) {
        int rows = alarmDao.update(null, new LambdaUpdateWrapper<AlarmEntity>()
                .eq(AlarmEntity::getId, alarmId)
                .eq(AlarmEntity::getHandled, 0)
                .set(AlarmEntity::getHandled, 1)
                .set(AlarmEntity::getHandler, userId)
                .set(AlarmEntity::getHandledTime, System.currentTimeMillis()));
        if (rows == 0) {
            return false;
        }
        AlarmEntity alarm = alarmDao.selectById(alarmId);
        if (alarm != null) {
            String handledEnvelope = publisher.buildEnvelope(alarm, "HANDLED");
            outboxService.enqueue("alarm:" + alarmId + ":HANDLED", AlarmOutboxPublisher.EVENT_TYPE_ALARM,
                    handledEnvelope, TraceIdFilter.currentOrGenerate());
            webhookNotifier.notify("HANDLED", handledEnvelope);
        }
        log.info("告警人工处理 alarmId={} handler={}", alarmId, userId);
        return true;
    }

    /** 管理端列表（默认未处理，倒序，限量） */
    public List<AlarmEntity> list(Integer handled, int limit) {
        return alarmDao.selectList(new LambdaQueryWrapper<AlarmEntity>()
                .eq(handled != null, AlarmEntity::getHandled, handled)
                .orderByDesc(AlarmEntity::getCreateTime)
                .last("LIMIT " + Math.min(limit, 500)));
    }

    private boolean rateLimited(AlarmType type) {
        try {
            String key = SwapRedisKeys.ALARM_RATE_PREFIX + type;
            long now = System.currentTimeMillis();
            stringRedisTemplate.opsForZSet().add(key, now + "-" + UUID.randomUUID().toString().substring(0, 6), now);
            stringRedisTemplate.opsForZSet().removeRangeByScore(key, 0, now - 60_000);
            Long count = stringRedisTemplate.opsForZSet().zCard(key);
            stringRedisTemplate.expire(key, Duration.ofMinutes(2));
            return count != null && count > properties.getRateLimitPerMinute();
        } catch (RuntimeException e) {
            return false; // Redis 故障不限速（宁可多告警）
        }
    }

    private void clearDedup(AlarmType type, String deviceNo) {
        try {
            stringRedisTemplate.delete(SwapRedisKeys.ALARM_DEDUP_PREFIX + type + ":" + deviceNo);
        } catch (RuntimeException e) {
            log.debug("告警去重键清理异常（TTL 兜底）: {}", e.getMessage());
        }
    }
}
