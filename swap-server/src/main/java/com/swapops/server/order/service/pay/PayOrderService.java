package com.swapops.server.order.service.pay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.common.RRException;
import com.swapops.server.common.id.SnowflakeIdGenerator;
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
    private final SnowflakeIdGenerator idGenerator;

    public PayOrderService(PayOrderDao payOrderDao, WalletService walletService,
                           PaymentRecordService paymentRecordService,
                           PaySignatureService paySignatureService, PayProperties payProperties,
                           SnowflakeIdGenerator idGenerator) {
        this.payOrderDao = payOrderDao;
        this.walletService = walletService;
        this.paymentRecordService = paymentRecordService;
        this.paySignatureService = paySignatureService;
        this.payProperties = payProperties;
        this.idGenerator = idGenerator;
    }

    /** 创建充值单（WAIT） */
    public PayOrderEntity createRecharge(Long userId, int amountFen) {
        if (amountFen < 1 || amountFen > payProperties.getMaxRechargeFen()) {
            throw new RRException("充值金额非法（1~" + payProperties.getMaxRechargeFen() + " 分）");
        }
        long now = System.currentTimeMillis();
        PayOrderEntity order = new PayOrderEntity();
        order.setTradeNo("R" + idGenerator.nextIdString());
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
        long now = System.currentTimeMillis();
        if (RESULT_SUCCESS.equalsIgnoreCase(result)) {
            // 终态仲裁（S3.8，资金优先）：SUCCESS 以网关实收为准——即使此前被 FAIL 关闭也补记入账；
            // CAS 是唯一入账权（并发/重复回调只有一个赢家，不会双记）
            PayOrderEntity accepted = tryAcceptSuccess(order, tradeNo, now);
            if (accepted == null) {
                PayOrderEntity current = payOrderDao.selectById(order.getId());
                return current == null ? order : current;
            }
            walletService.addBalance(order.getUserId(), order.getAmountFen());
            paymentRecordService.record(order.getUserId(), null, PaymentType.RECHARGE,
                    order.getAmountFen(), "充值:" + tradeNo);
            log.info("充值入账成功 tradeNo={} userId={} amountFen={}",
                    tradeNo, order.getUserId(), order.getAmountFen());
            return accepted;
        }
        // 失败结果：仅在 WAIT 生效；已 SUCCESS 视为无效的"伪失败"（资金优先，不回退）；已 CLOSED 幂等
        int rows = casStatus(order.getId(), PayOrderStatus.WAIT, PayOrderStatus.CLOSED, now);
        if (rows > 0) {
            order.setStatus(PayOrderStatus.CLOSED.name());
            order.setCallbackTime(now);
            log.warn("充值支付失败/取消，单据关闭 tradeNo={} result={}", tradeNo, result);
            return order;
        }
        PayOrderEntity current = payOrderDao.selectById(order.getId());
        if (current != null && (PayOrderStatus.SUCCESS.name().equals(current.getStatus())
                || PayOrderStatus.CLOSED.name().equals(current.getStatus()))) {
            return current;
        }
        throw new RRException("支付单状态冲突: " + tradeNo);
    }

    /**
     * 尝试取得"成功入账权"（幂等闸）。
     * WAIT→SUCCESS 命中即赢；未命中：已 SUCCESS=他人已入账（返回 null），
     * 已 CLOSED=迟到的成功（网关实收必须补记，再 CAS CLOSED→SUCCESS）；仍失败则冲突拒绝。
     *
     * @return 赢得入账权的单据（调用方执行资金动作）；null=无需重复入账
     */
    private PayOrderEntity tryAcceptSuccess(PayOrderEntity order, String tradeNo, long now) {
        if (casStatus(order.getId(), PayOrderStatus.WAIT, PayOrderStatus.SUCCESS, now) > 0) {
            order.setStatus(PayOrderStatus.SUCCESS.name());
            order.setCallbackTime(now);
            return order;
        }
        PayOrderEntity current = payOrderDao.selectById(order.getId());
        if (current == null || PayOrderStatus.SUCCESS.name().equals(current.getStatus())) {
            return null;
        }
        if (PayOrderStatus.CLOSED.name().equals(current.getStatus())) {
            if (casStatus(order.getId(), PayOrderStatus.CLOSED, PayOrderStatus.SUCCESS, now) > 0) {
                log.warn("迟到的成功回调：CLOSED→SUCCESS 补记入账 tradeNo={}", tradeNo);
                current.setStatus(PayOrderStatus.SUCCESS.name());
                current.setCallbackTime(now);
                return current;
            }
            PayOrderEntity again = payOrderDao.selectById(order.getId());
            if (again != null && PayOrderStatus.SUCCESS.name().equals(again.getStatus())) {
                return null; // 并发迟成功已被对方补记
            }
        }
        throw new RRException("支付单状态冲突: " + tradeNo);
    }

    private int casStatus(Long id, PayOrderStatus from, PayOrderStatus to, long now) {
        return payOrderDao.update(null, new LambdaUpdateWrapper<PayOrderEntity>()
                .eq(PayOrderEntity::getId, id)
                .eq(PayOrderEntity::getStatus, from.name())
                .set(PayOrderEntity::getStatus, to.name())
                .set(PayOrderEntity::getCallbackTime, now)
                .set(PayOrderEntity::getUpdateTime, now));
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
