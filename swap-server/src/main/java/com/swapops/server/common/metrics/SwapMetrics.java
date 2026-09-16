package com.swapops.server.common.metrics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.server.alarm.dao.AlarmDao;
import com.swapops.server.alarm.entity.AlarmEntity;
import com.swapops.server.common.delay.DelayQueueService;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.order.service.AllocationService;
import com.swapops.server.order.service.delay.OrderDelayTopics;
import com.swapops.server.outbox.dao.OutboxEventDao;
import com.swapops.server.outbox.entity.OutboxEventEntity;
import com.swapops.server.reconcile.DailyReconcileTask;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 业务指标（P1-6 可观测性）：Gauge 供应商式注册——Prometheus 每次抓取时求值。
 * 覆盖：outbox 积压/死信、未处理告警、延迟任务积压（按 topic）、最近对账差异、可分配满电仓总量。
 * 求值异常统一降级为 -1（不向抓取链路抛错）；计数直接 SELECT COUNT（演示/单机小基数口径，大表勿照抄）。
 */
@Slf4j
@Component
public class SwapMetrics {

    private static final List<String> DELAY_TOPICS = List.of(
            OrderDelayTopics.PREEMPT_TIMEOUT, OrderDelayTopics.PICKUP_TIMEOUT, OrderDelayTopics.OVERDUE);

    private final MeterRegistry registry;
    private final OutboxEventDao outboxEventDao;
    private final AlarmDao alarmDao;
    private final DelayQueueService delayQueueService;
    private final AllocationService allocationService;
    private final CabinetDao cabinetDao;
    private final DailyReconcileTask dailyReconcileTask;

    public SwapMetrics(MeterRegistry registry, OutboxEventDao outboxEventDao, AlarmDao alarmDao,
                       DelayQueueService delayQueueService, AllocationService allocationService,
                       CabinetDao cabinetDao, DailyReconcileTask dailyReconcileTask) {
        this.registry = registry;
        this.outboxEventDao = outboxEventDao;
        this.alarmDao = alarmDao;
        this.delayQueueService = delayQueueService;
        this.allocationService = allocationService;
        this.cabinetDao = cabinetDao;
        this.dailyReconcileTask = dailyReconcileTask;
        registerGauges();
    }

    private void registerGauges() {
        // strongReference(true)：Gauge 默认弱引用持有实例，一旦被 GC 指标静默变 NaN；
        // SwapMetrics 是 Spring 单例（生命周期=应用），显式强引用消除该隐患
        Gauge.builder("swap.outbox.backlog", this, m -> m.safe(m::outboxBacklog))
                .strongReference(true)
                .description("outbox 待投递消息数（status=NEW）").register(registry);
        Gauge.builder("swap.outbox.dead", this, m -> m.safe(m::outboxDead))
                .strongReference(true)
                .description("outbox 死信数（status=DEAD）").register(registry);
        Gauge.builder("swap.alarm.unhandled", this, m -> m.safe(m::alarmUnhandled))
                .strongReference(true)
                .description("未处理告警数（handled=0）").register(registry);
        for (String topic : DELAY_TOPICS) {
            Gauge.builder("swap.delay.backlog", this, m -> m.safe(() -> m.delayBacklog(topic)))
                    .strongReference(true)
                    .tag("topic", topic).description("延迟任务积压（按 topic）").register(registry);
        }
        Gauge.builder("swap.reconcile.violations", this, m -> m.safe(m::reconcileViolations))
                .strongReference(true)
                .description("最近一次对账差异总数（-1=报告不可得）").register(registry);
        Gauge.builder("swap.alloc.available", this, m -> m.safe(m::allocAvailableFull))
                .strongReference(true)
                .description("全部可用柜的可分配满电仓总数").register(registry);
    }

    private double outboxBacklog() {
        Long n = outboxEventDao.selectCount(new LambdaQueryWrapper<OutboxEventEntity>()
                .eq(OutboxEventEntity::getStatus, "NEW"));
        return n == null ? -1 : n;
    }

    private double outboxDead() {
        Long n = outboxEventDao.selectCount(new LambdaQueryWrapper<OutboxEventEntity>()
                .eq(OutboxEventEntity::getStatus, "DEAD"));
        return n == null ? -1 : n;
    }

    private double alarmUnhandled() {
        Long n = alarmDao.selectCount(new LambdaQueryWrapper<AlarmEntity>()
                .eq(AlarmEntity::getHandled, 0));
        return n == null ? -1 : n;
    }

    private double delayBacklog(String topic) {
        return delayQueueService.size(topic);
    }

    /** 最近对账报告（Redis 缓存，DailyReconcileTask 落）；报告缺失返回 -1（不伪报 0） */
    private double reconcileViolations() {
        Map<String, Object> report = dailyReconcileTask.lastReport();
        if (report == null) {
            return -1;
        }
        Object v = report.get("totalViolations");
        return v instanceof Number n ? n.doubleValue() : -1;
    }

    /** 可用柜（status<=2）的满电可分配仓总数（逐柜 SCARD 聚合） */
    private double allocAvailableFull() {
        long total = 0;
        for (CabinetEntity cabinet : cabinetDao.selectList(null)) {
            if (cabinet.getCabinetNo() != null && cabinet.getStatus() != null && cabinet.getStatus() <= 2) {
                total += allocationService.countAvailable(cabinet.getCabinetNo(), true);
            }
        }
        return total;
    }

    private double safe(Supplier<? extends Number> supplier) {
        try {
            Number n = supplier.get();
            return n == null ? -1 : n.doubleValue();
        } catch (Exception e) {
            log.warn("[metrics] 指标求值失败（降级 -1）: {}", e.getMessage());
            return -1;
        }
    }
}
