package com.swapops.server.order.service.delay;

/**
 * 订单延迟任务主题（S3.3）：一个订单在任一时刻只应有一个生效计时器，
 * 状态推进时取消旧计时器并登记下一段（taskId=orderNo）。
 */
public final class OrderDelayTopics {

    /** 下单后等待开仓/取电（preemptExpireTime 到期） */
    public static final String PREEMPT_TIMEOUT = "order-preempt-timeout";

    /** 开仓后等待取电/还电（pickupTimeout 到期） */
    public static final String PICKUP_TIMEOUT = "order-pickup-timeout";

    /** SWAP 取电后归还超期（overdueHours 到期，转 OVERDUE 计费） */
    public static final String OVERDUE = "order-overdue";

    private OrderDelayTopics() {
    }
}
