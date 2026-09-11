package com.swapops.server.order.service;

import com.swapops.server.common.id.SnowflakeIdGenerator;
import com.swapops.server.order.dao.PaymentRecordDao;
import com.swapops.server.order.entity.PaymentRecordEntity;
import com.swapops.server.order.enums.PaymentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 支付流水：{order_id, payment_type} 唯一键是资金动作的幂等闸——重复投递/重放不会二次扣款。
 */
@Slf4j
@Service
public class PaymentRecordService {

    private final PaymentRecordDao paymentRecordDao;
    private final SnowflakeIdGenerator idGenerator;

    public PaymentRecordService(PaymentRecordDao paymentRecordDao, SnowflakeIdGenerator idGenerator) {
        this.paymentRecordDao = paymentRecordDao;
        this.idGenerator = idGenerator;
    }

    /**
     * 记录一次资金动作。
     *
     * @return true=首次写入；false=已存在（幂等重放，调用方应跳过后续资金操作）
     */
    public boolean record(Long userId, Long orderId, PaymentType type, int amountFen, String remark) {
        PaymentRecordEntity entity = new PaymentRecordEntity();
        entity.setUserId(userId);
        entity.setOrderId(orderId);
        entity.setPaymentType(type.name());
        entity.setAmountFen(amountFen);
        entity.setChannel("MOCK");
        entity.setTradeNo("PAY" + idGenerator.nextIdString());
        entity.setStatus(1);
        entity.setRemark(remark);
        entity.setCreateTime(System.currentTimeMillis());
        try {
            paymentRecordDao.insert(entity);
            return true;
        } catch (DuplicateKeyException e) {
            log.info("支付流水幂等命中（已记录） userId={} orderId={} type={}", userId, orderId, type);
            return false;
        }
    }
}
