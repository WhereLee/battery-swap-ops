package com.swapops.server.alarm.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.config.AlarmProperties;
import com.swapops.server.alarm.dao.AlarmDao;
import com.swapops.server.alarm.entity.AlarmEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 告警治理单测（S3.6）：去重/限速/DB 兜底/恢复/人工处理。
 */
@DisplayName("告警治理（去重+限速+恢复）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlarmServiceTest {

    @Mock
    private AlarmDao alarmDao;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ZSetOperations<String, String> zset;
    @Mock
    private AlarmEventPublisher publisher;
    @Mock
    private com.swapops.server.outbox.service.OutboxService outboxService;
    @Mock
    private AlarmWebhookNotifier webhookNotifier;

    private AlarmService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AlarmEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new AlarmService(alarmDao, redis, new AlarmProperties(), publisher, outboxService, webhookNotifier);
        when(publisher.buildEnvelope(any(), anyString())).thenReturn("{\"alarmId\":1}");
    }

    private void givenRedisFirstSeen(boolean first) {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(first);
    }

    private void givenRateLimitCount(long count) {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.add(anyString(), anyString(), anyDouble())).thenReturn(true);
        when(zset.removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(0L);
        when(zset.zCard(anyString())).thenReturn(count);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
    }

    @Test
    @DisplayName("首次告警：入库 + 事件入 outbox（与告警同事务）")
    void 首次告警入库() {
        givenRedisFirstSeen(true);
        givenRateLimitCount(1);
        when(alarmDao.selectOne(any())).thenReturn(null);
        when(alarmDao.insert(any(AlarmEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, AlarmEntity.class).setId(1L);
            return 1;
        });

        Long id = service.raise(AlarmService.DEVICE_CABINET, "SWAP-C-001", AlarmType.OFFLINE, "离线");

        assertThat(id).isEqualTo(1L);
        verify(outboxService).enqueue(eq("alarm:1:RAISED"), eq("ALARM"), anyString(), anyString());
        verify(webhookNotifier).notify(eq("RAISED"), anyString());
    }

    @Test
    @DisplayName("去重窗口命中：不入库不发布")
    void 去重命中() {
        givenRedisFirstSeen(false);

        assertThat(service.raise(AlarmService.DEVICE_CABINET, "SWAP-C-001", AlarmType.OFFLINE, "离线"))
                .isNull();
        verifyNoInteractions(alarmDao, outboxService);
    }

    @Test
    @DisplayName("类型限速超限：只日志不入库")
    void 限速丢弃() {
        givenRedisFirstSeen(true);
        givenRateLimitCount(31);

        assertThat(service.raise(AlarmService.DEVICE_CABINET, "SWAP-C-001", AlarmType.OFFLINE, "离线"))
                .isNull();
        verifyNoInteractions(alarmDao, outboxService);
    }

    @Test
    @DisplayName("DB 时间窗兜底：同 key 未处理告警已存在 → 不重复入库")
    void DB兜底() {
        givenRedisFirstSeen(true);
        givenRateLimitCount(1);
        AlarmEntity existing = new AlarmEntity();
        existing.setId(9L);
        when(alarmDao.selectOne(any())).thenReturn(existing);

        assertThat(service.raise(AlarmService.DEVICE_CABINET, "SWAP-C-001", AlarmType.OFFLINE, "离线"))
                .isEqualTo(9L);
        verify(alarmDao, never()).insert(any(AlarmEntity.class));
    }

    @Test
    @DisplayName("自动恢复：关闭未处理告警并清去重键")
    void 自动恢复() {
        when(redis.delete(anyString())).thenReturn(true);
        when(alarmDao.update(isNull(), any())).thenReturn(2);

        assertThat(service.markRecovered(AlarmService.DEVICE_CABINET, "SWAP-C-001", AlarmType.OFFLINE))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("自动恢复：关闭后出站 RECOVERED webhook 通知（P1-11）")
    void 恢复出站通知() {
        when(redis.delete(anyString())).thenReturn(true);
        AlarmEntity open = new AlarmEntity();
        open.setId(3L);
        when(alarmDao.selectList(any())).thenReturn(java.util.List.of(open));
        when(alarmDao.update(isNull(), any())).thenReturn(1);

        assertThat(service.markRecovered(AlarmService.DEVICE_CABINET, "SWAP-C-001", AlarmType.OFFLINE))
                .isEqualTo(1);
        verify(webhookNotifier).notify(eq("RECOVERED"), anyString());
    }

    @Test
    @DisplayName("人工处理：CAS 未命中拒绝；命中处理事件入 outbox（审计可补投）")
    void 人工处理() {
        when(alarmDao.update(isNull(), any())).thenReturn(0);
        assertThat(service.handle(5L, 7L)).isFalse();
        verifyNoInteractions(outboxService);

        when(alarmDao.update(isNull(), any())).thenReturn(1);
        when(alarmDao.selectById(5L)).thenReturn(new AlarmEntity());
        assertThat(service.handle(5L, 7L)).isTrue();
        verify(outboxService).enqueue(eq("alarm:5:HANDLED"), eq("ALARM"), anyString(), anyString());
        verify(webhookNotifier).notify(eq("HANDLED"), anyString());
    }

    @Test
    @DisplayName("Redis 故障：去重 fail-open 继续入库")
    void redis故障放行() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
        when(redis.opsForZSet()).thenThrow(new RuntimeException("redis down"));
        when(alarmDao.selectOne(any())).thenReturn(null);
        when(alarmDao.insert(any(AlarmEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, AlarmEntity.class).setId(2L);
            return 1;
        });

        assertThat(service.raise(AlarmService.DEVICE_CABINET, "SWAP-C-001", AlarmType.OFFLINE, "离线"))
                .isEqualTo(2L);
    }
}
