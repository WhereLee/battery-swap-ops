package com.swapops.server.order.service.pay;

import com.swapops.server.common.delay.DelayTaskHandler;
import com.swapops.server.order.entity.RefundRecordEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 退款重试处理器（S3.4）：延迟队列到期重执行 apply；
 * 失败继续抛出 → 调度器退避重投 → 超限死信（S3.6 告警 DELAY_DEAD）。
 */
@Slf4j
@Component
public class RefundApplyHandler implements DelayTaskHandler {

    private final RefundService refundService;

    public RefundApplyHandler(RefundService refundService) {
        this.refundService = refundService;
    }

    @Override
    public String topic() {
        return RefundService.REFUND_TOPIC;
    }

    @Override
    public void handle(String payload) {
        RefundRecordEntity record = refundService.findByRefundNo(payload);
        if (record == null) {
            log.warn("退款重试单不存在，跳过 refundNo={}", payload);
            return;
        }
        refundService.apply(record);
    }
}
