package com.swapops.server.order.service.pay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.contract.OrderStatus;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 异常订单退款补偿（S3.4）：订单转 EXCEPTION（设备故障/证据丢失）后，
 * 将已收费用（基础费/押金/超时费）自动退回余额；同订单同原因幂等（退款单唯一键）。
 * 无应退金额也落 0 元退款单作为"已核"标记（避免每轮重复扫描判定）。
 */
@Slf4j
@Component
public class RefundCompensationTask {

    private static final int BATCH = 50;
    private static final String REASON = "ORDER_EXCEPTION";

    private final SwapOrderDao orderDao;
    private final RefundService refundService;
    private final JobLockService jobLockService;

    public RefundCompensationTask(SwapOrderDao orderDao, RefundService refundService, JobLockService jobLockService) {
        this.orderDao = orderDao;
        this.refundService = refundService;
        this.jobLockService = jobLockService;
    }

    @Scheduled(fixedDelayString = "${swap.pay.refund-compensation-interval-ms:30000}")
    public void compensate() {
        if (!jobLockService.runWithLock("refund-compensation", this::doCompensate)) {
            log.debug("退款补偿：另一实例执行中，跳过本轮");
        }
    }

    private void doCompensate() {
        List<SwapOrderEntity> exceptions = orderDao.selectList(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getStatus, OrderStatus.EXCEPTION.getCode())
                .orderByDesc(SwapOrderEntity::getId)
                .last("LIMIT " + BATCH));
        for (SwapOrderEntity order : exceptions) {
            int amount = refundService.refundableAmount(order.getId());
            refundService.refund(order.getId(), order.getUserId(), amount, REASON);
        }
    }
}
