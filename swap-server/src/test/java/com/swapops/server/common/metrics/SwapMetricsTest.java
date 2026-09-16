package com.swapops.server.common.metrics;

import com.swapops.server.alarm.dao.AlarmDao;
import com.swapops.server.common.delay.DelayQueueService;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.order.service.AllocationService;
import com.swapops.server.order.service.delay.OrderDelayTopics;
import com.swapops.server.outbox.dao.OutboxEventDao;
import com.swapops.server.reconcile.DailyReconcileTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * P1-6 业务指标单测：注册齐全、供应商取值、异常降级 -1。
 */
@DisplayName("P1-6 业务指标（Gauge 供应商）")
@ExtendWith(MockitoExtension.class)
class SwapMetricsTest {

    @Mock
    private OutboxEventDao outboxEventDao;
    @Mock
    private AlarmDao alarmDao;
    @Mock
    private DelayQueueService delayQueueService;
    @Mock
    private AllocationService allocationService;
    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private DailyReconcileTask dailyReconcileTask;

    private SimpleMeterRegistry registry;
    /** 保持强引用（模拟 Spring 容器持有单例）——否则 GC 后 Gauge 弱引用失效，值静默变 NaN */
    private SwapMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SwapMetrics(registry, outboxEventDao, alarmDao, delayQueueService, allocationService,
                cabinetDao, dailyReconcileTask);
    }

    @Test
    @DisplayName("六项业务指标注册齐全且随供应商取值")
    void 指标注册与取值() {
        when(outboxEventDao.selectCount(any())).thenReturn(3L);
        when(alarmDao.selectCount(any())).thenReturn(2L);
        when(delayQueueService.size(OrderDelayTopics.PREEMPT_TIMEOUT)).thenReturn(5L);
        when(delayQueueService.size(OrderDelayTopics.PICKUP_TIMEOUT)).thenReturn(1L);
        when(delayQueueService.size(OrderDelayTopics.OVERDUE)).thenReturn(0L);
        when(dailyReconcileTask.lastReport()).thenReturn(Map.of("totalViolations", 0));
        CabinetEntity c1 = new CabinetEntity();
        c1.setCabinetNo("SWAP-C-001");
        c1.setStatus(1);
        when(cabinetDao.selectList(any())).thenReturn(List.of(c1));
        when(allocationService.countAvailable(anyString(), eq(true))).thenReturn(6L);

        assertThat(registry.get("swap.outbox.backlog").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("swap.outbox.dead").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("swap.alarm.unhandled").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("swap.delay.backlog").tag("topic", OrderDelayTopics.PREEMPT_TIMEOUT)
                .gauge().value()).isEqualTo(5.0);
        assertThat(registry.get("swap.delay.backlog").tag("topic", OrderDelayTopics.PICKUP_TIMEOUT)
                .gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("swap.delay.backlog").tag("topic", OrderDelayTopics.OVERDUE)
                .gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("swap.reconcile.violations").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("swap.alloc.available").gauge().value()).isEqualTo(6.0);
    }

    @Test
    @DisplayName("求值异常降级为 -1，不抛给抓取链路")
    void 异常降级() {
        when(outboxEventDao.selectCount(any())).thenThrow(new RuntimeException("db down"));
        when(dailyReconcileTask.lastReport()).thenReturn(null);

        assertThat(registry.get("swap.outbox.backlog").gauge().value()).isEqualTo(-1.0);
        assertThat(registry.get("swap.reconcile.violations").gauge().value()).isEqualTo(-1.0);
    }
}
