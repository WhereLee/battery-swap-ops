package com.swapops.server.order.service.delay;

import com.swapops.server.common.delay.DelayQueueService;
import com.swapops.server.config.BillingProperties;
import com.swapops.server.order.entity.SwapOrderEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 订单延迟任务登记（S3.3）：把订单状态机的"下一段超时"登记为精确计时器
 * （替代纯扫描轮询；扫描任务保留为兜底/补偿）。
 *
 * <p>每次状态推进遵循"先取消旧计时、再登记新计时"，taskId=orderNo 天然去重。
 * 登记失败不阻断主流程（扫描任务兜底），仅记警告。</p>
 */
@Slf4j
@Service
public class OrderDelayService {

    private final DelayQueueService delayQueueService;
    private final BillingProperties billingProperties;

    public OrderDelayService(DelayQueueService delayQueueService, BillingProperties billingProperties) {
        this.delayQueueService = delayQueueService;
        this.billingProperties = billingProperties;
    }

    /** 下单后：等待开仓/取电（到期扫描兜底关闭） */
    public void schedulePreempt(SwapOrderEntity order) {
        safeEnqueue(OrderDelayTopics.PREEMPT_TIMEOUT, order.getOrderNo(), order.getPreemptExpireTime());
    }

    /** 开仓后：等待取电（OPENED 起点以事件/证据时刻为准） */
    public void schedulePickup(SwapOrderEntity order, long openTimeMillis) {
        safeEnqueue(OrderDelayTopics.PICKUP_TIMEOUT, order.getOrderNo(),
                openTimeMillis + billingProperties.getPickupTimeoutSeconds() * 1000L);
    }

    /** SWAP 取电后：归还超期计时（到期转 OVERDUE，进入超时计费） */
    public void scheduleOverdue(SwapOrderEntity order, long takeTimeMillis) {
        safeEnqueue(OrderDelayTopics.OVERDUE, order.getOrderNo(),
                takeTimeMillis + billingProperties.getOverdueHours() * 3600_000L);
    }

    /** 订单终态/关闭：清掉全部登记（下一段不存在的计时器到时也会因状态不符而空转一次，但清理更干净） */
    public void cancelAll(SwapOrderEntity order) {
        cancelAll(order.getOrderNo());
    }

    public void cancelAll(String orderNo) {
        try {
            delayQueueService.cancel(OrderDelayTopics.PREEMPT_TIMEOUT, orderNo);
            delayQueueService.cancel(OrderDelayTopics.PICKUP_TIMEOUT, orderNo);
            delayQueueService.cancel(OrderDelayTopics.OVERDUE, orderNo);
        } catch (RuntimeException e) {
            log.warn("订单计时器取消失败（终态判定保证空转无害） orderNo={} cause={}", orderNo, e.getMessage());
        }
    }

    private void safeEnqueue(String topic, String orderNo, long executeAtMillis) {
        if (orderNo == null || executeAtMillis <= 0) {
            return;
        }
        try {
            delayQueueService.enqueue(topic, orderNo, OrderDelayPayload.of(orderNo), executeAtMillis);
        } catch (RuntimeException e) {
            log.warn("订单计时器登记失败（扫描任务兜底） topic={} orderNo={} cause={}",
                    topic, orderNo, e.getMessage());
        }
    }
}
