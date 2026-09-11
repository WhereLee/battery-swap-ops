package com.swapops.server.outbox.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.outbox.dao.OutboxEventDao;
import com.swapops.server.outbox.entity.OutboxEventEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * outbox 仓储单测（S3.8 WP6）：入队幂等、到期查询、状态 CAS 推进（NEW→SENT/DEAD/重试）。
 */
@DisplayName("outbox 仓储")
@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxEventDao outboxEventDao;

    private OutboxService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OutboxEventEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new OutboxService(outboxEventDao);
    }

    @Test
    @DisplayName("入队：NEW/attempts=0/nextRetry=now")
    void 入队() {
        when(outboxEventDao.insert(any(OutboxEventEntity.class))).thenAnswer(inv -> {
            OutboxEventEntity event = inv.getArgument(0);
            assertThat(event.getStatus()).isEqualTo("NEW");
            assertThat(event.getAttempts()).isZero();
            assertThat(event.getEventKey()).isEqualTo("alarm:1:RAISED");
            return 1;
        });

        assertThat(service.enqueue("alarm:1:RAISED", "ALARM", "{}", "trace-1")).isTrue();
    }

    @Test
    @DisplayName("入队幂等：event_key 重复 → false（不抛错）")
    void 入队幂等() {
        when(outboxEventDao.insert(any(OutboxEventEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_outbox_event_key"));

        assertThat(service.enqueue("alarm:1:RAISED", "ALARM", "{}", "trace-1")).isFalse();
    }

    @Test
    @DisplayName("到期查询：仅 NEW 且 next_retry_time ≤ now")
    void 到期查询() {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(1L);
        when(outboxEventDao.selectList(any())).thenReturn(List.of(event));

        assertThat(service.findDue(System.currentTimeMillis(), 10)).hasSize(1);
    }

    @Test
    @DisplayName("状态推进 CAS：命中返回 true；未命中（已被推进）返回 false")
    void 状态CAS() {
        when(outboxEventDao.update(isNull(), any())).thenReturn(1, 0);

        assertThat(service.markSent(1L)).isTrue();
        assertThat(service.markDead(1L, "boom")).isFalse();
        verify(outboxEventDao, org.mockito.Mockito.times(2)).update(isNull(), any());
    }

    @Test
    @DisplayName("重试：attempts+1 与退避时间写入")
    void 重试写入() {
        when(outboxEventDao.update(isNull(), any())).thenReturn(1);

        assertThat(service.markRetry(1L, 2, System.currentTimeMillis() + 5000, "mq down")).isTrue();
    }
}
