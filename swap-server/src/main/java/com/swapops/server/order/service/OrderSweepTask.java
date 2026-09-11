package com.swapops.server.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.contract.OrderStatus;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.config.BillingProperties;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单超时扫描（S2 基础版；S3.3 起为延迟任务调度器的**兜底补偿**）：
 * <ul>
 *   <li>PENDING_OPEN 超过预占 TTL（未开仓/指令未达）→ TIMEOUT_CLOSED + 释放预占；</li>
 *   <li>OPENED 超过取电/还电等待窗口 → TIMEOUT_CLOSED + 释放（电池仍在仓则由集合刷新兜底）。</li>
 * </ul>
 * 每轮限量并 CAS 关闭，避免与设备事件竞争（CAS 未命中=已推进，跳过）；
 * 多实例由 {@link JobLockService} 单实例执行（计时器丢失/Redis 故障时的最终防线，必须能跑）。
 */
@Slf4j
@Component
public class OrderSweepTask {

    private static final int BATCH = 100;

    private final SwapOrderDao orderDao;
    private final SwapOrderService swapOrderService;
    private final BillingProperties billingProperties;
    private final JobLockService jobLockService;

    public OrderSweepTask(SwapOrderDao orderDao, SwapOrderService swapOrderService,
                          BillingProperties billingProperties, JobLockService jobLockService) {
        this.orderDao = orderDao;
        this.swapOrderService = swapOrderService;
        this.billingProperties = billingProperties;
        this.jobLockService = jobLockService;
    }

    @Scheduled(fixedDelayString = "${swap.order.sweep-interval-ms:5000}")
    public void sweep() {
        if (!jobLockService.runWithLock("order-sweep", this::doSweep)) {
            log.debug("订单扫描：另一实例执行中，跳过本轮");
        }
    }

    private void doSweep() {
        long now = System.currentTimeMillis();
        List<SwapOrderEntity> preemptTimeouts = orderDao.selectList(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getStatus, OrderStatus.PENDING_OPEN.getCode())
                .lt(SwapOrderEntity::getPreemptExpireTime, now)
                .last("LIMIT " + BATCH));
        for (SwapOrderEntity order : preemptTimeouts) {
            swapOrderService.closeTimedOut(order, "PREEMPT_TIMEOUT");
        }
        long pickupDeadline = now - billingProperties.getPickupTimeoutSeconds() * 1000L;
        List<SwapOrderEntity> pickupTimeouts = orderDao.selectList(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getStatus, OrderStatus.OPENED.getCode())
                .lt(SwapOrderEntity::getOpenTime, pickupDeadline)
                .last("LIMIT " + BATCH));
        for (SwapOrderEntity order : pickupTimeouts) {
            swapOrderService.closeTimedOut(order, "PICKUP_TIMEOUT");
        }
    }
}
