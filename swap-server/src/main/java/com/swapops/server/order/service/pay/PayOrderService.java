package com.swapops.server.order.service.pay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.common.RRException;
import com.swapops.server.config.PayProperties;
import com.swapops.server.order.dao.PayOrderDao;
import com.swapops.server.order.entity.PayOrderEntity;
import com.swapops.server.order.enums.PayOrderStatus;
import com.swapops.server.order.enums.PaymentType;
import com.swapops.server.order.service.PaymentRecordService;
import com.swapops.server.user.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 充值支付单（S3.4）：创建 WAIT 单 → 网关回调 → CAS 命中才入账（幂等）。
 * 回调验签失败/单据不存在 = fail-fast；重复回调 = 幂等返回成功。
 */
@Slf4j
@Service
public class PayOrderService {

    private static final String RESULT_SUCCESS = "SUCCESS";

    private final PayOrderDao payOrderDao;
    private final WalletService walletService;
    private final PaymentRecordService paymentRecordService;
    private final PaySignatureService paySignatureService;
    private final PayProperties payProperties;

    public PayOrderService(PayOrderDao payOrderDao, WalletService walletService,
                           PaymentRecordService paymentRecordService,
                           PaySignatureService paySignatureService, PayProperties payProperties) {
        this.payOrderDao = payOrderDao;
        this.walletService = walletService;
        this.paymentRecordService = paymentRecordService;
        this.paySignatureService = paySignatureService;
        this.payProperties = payProperties;
    }

    /** 创建充值单（WAIT） */
    public PayOrderEntity createRecharge(Long userId, int amountFen) {
        if (amountFen < 1 || amountFen > payProperties.getMaxRechargeFen()) {
            throw new RRException("充值金额非法（1~" + payProperties.getMaxRechargeFen() + " 分）");
        }
        long now = System.currentTimeMillis();
        PayOrderEntity order = new PayOrderEntity();
        order.setTradeNo("R" + now + UUID.randomUUID().toString().replace("-", "").substring(0, 6));
        order.setUserId(userId);
        order.setAmountFen(amountFen);
        order.setPurpose("RECHARGE");
        order.setStatus(PayOrderStatus.WAIT.name());
        order.setChannel("MOCK");
        order.setCreateTime(now);
        order.setUpdateTime(now);
        payOrderDao.insert(order);
        log.info("充值单创建 tradeNo={} userId={} amountFen={}", order.getTradeNo(), userId, amountFen);
        return order;
    }

    public PayOrderEntity findByTradeNo(String tradeNo) {
        return payOrderDao.selectOne(new LambdaQueryWrapper<PayOrderEntity>()
                .eq(PayOrderEntity::getTradeNo, tradeNo));
    }

    /**
     * 网关回调（验签 → CAS WAIT→SUCCESS/CLOSED → 资金动作）。
     * CAS 命中才入账；未命中（并发/重复）重读状态：SUCCESS 幂等返回，否则拒绝。
     */
    @Transactional
    public PayOrderEntity handleCallback(String tradeNo, String result, String sign) {
        if (!paySignatureService.verify(tradeNo, result, sign)) {
            log.error("支付回调验签失败 tradeNo={} result={}", tradeNo, result);
            throw new RRException("支付回调签名非法");
        }
        PayOrderEntity order = findByTradeNo(tradeNo);
        if (order == null) {
            throw new RRException("支付单不存在: " + tradeNo);
        }
        if (PayOrderStatus.SUCCESS.name().equals(order.getStatus())) {
            log.info("支付回调幂等命中（已入账） tradeNo={}", tradeNo);
            return order;
        }
        if (PayOrderStatus.CLOSED.name().equals(order.getStatus())) {
            throw new RRException("支付单已关闭: " + tradeNo);
        }
        long now = System.currentTimeMillis();
        if (RESULT_SUCCESS.equalsIgnoreCase(result)) {
            int rows = payOrderDao.update(null, new LambdaUpdateWrapper<PayOrderEntity>()
                    .eq(PayOrderEntity::getId, order.getId())
                    .eq(PayOrderEntity::getStatus, PayOrderStatus.WAIT.name())
                    .set(PayOrderEntity::getStatus, PayOrderStatus.SUCCESS.name())
                    .set(PayOrderEntity::getCallbackTime, now)
                    .set(PayOrderEntity::getUpdateTime, now));
            if (rows == 0) {
                PayOrderEntity current = payOrderDao.selectById(order.getId());
                if (current != null && PayOrderStatus.SUCCESS.name().equals(current.getStatus())) {
                    return current; // 并发回调已被对方入账
                }
                throw new RRException("支付单状态冲突: " + tradeNo);
            }
            walletService.addBalance(order.getUserId(), order.getAmountFen());
            paymentRecordService.record(order.getUserId(), null, PaymentType.RECHARGE,
                    order.getAmountFen(), "充值:" + tradeNo);
            order.setStatus(PayOrderStatus.SUCCESS.name());
            order.setCallbackTime(now);
            log.info("充值入账成功 tradeNo={} userId={} amountFen={}",
                    tradeNo, order.getUserId(), order.getAmountFen());
            return order;
        }
        int rows = payOrderDao.update(null, new LambdaUpdateWrapper<PayOrderEntity>()
                .eq(PayOrderEntity::getId, order.getId())
                .eq(PayOrderEntity::getStatus, PayOrderStatus.WAIT.name())
                .set(PayOrderEntity::getStatus, PayOrderStatus.CLOSED.name())
                .set(PayOrderEntity::getCallbackTime, now)
                .set(PayOrderEntity::getUpdateTime, now));
        if (rows == 0) {
            PayOrderEntity current = payOrderDao.selectById(order.getId());
            if (current != null && PayOrderStatus.SUCCESS.name().equals(current.getStatus())) {
                return current; // 已入账：支付结果以成功为准
            }
            throw new RRException("支付单状态冲突: " + tradeNo);
        }
        order.setStatus(PayOrderStatus.CLOSED.name());
        order.setCallbackTime(now);
        log.warn("充值支付失败/取消，单据关闭 tradeNo={} result={}", tradeNo, result);
        return order;
    }

    /** 视图（充值单） */
    public Map<String, Object> view(PayOrderEntity order) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tradeNo", order.getTradeNo());
        view.put("amountFen", order.getAmountFen());
        view.put("purpose", order.getPurpose());
        view.put("status", order.getStatus());
        view.put("channel", order.getChannel());
        view.put("createTime", order.getCreateTime());
        view.put("callbackTime", order.getCallbackTime());
        view.put("payUrl", order.getCreateTime() == null ? null
                : payProperties.getMockPageBaseUrl() + "/page/" + order.getTradeNo());
        return view;
    }
}
