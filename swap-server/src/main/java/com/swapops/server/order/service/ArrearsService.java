package com.swapops.server.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.common.RRException;
import com.swapops.server.order.dao.ArrearsRecordDao;
import com.swapops.server.order.entity.ArrearsRecordEntity;
import com.swapops.server.user.service.UserMessageService;
import com.swapops.server.user.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 欠费闭环（S7 WP-D）：
 * <ul>
 *   <li>产生：计费超时费不足额 → 落/累加欠费单（同订单唯一，计费事务内）；</li>
 *   <li>门槛：TAKE/SWAP 存在 OPEN 欠费拒绝（RETURN 放行，防逼停归还）；</li>
 *   <li>结清：余额补缴（CAS 扣款同事务）或客服减免；结清后自动关 ORDER_ARREARS 告警 + 站内信。</li>
 * </ul>
 */
@Slf4j
@Service
public class ArrearsService {

    private final ArrearsRecordDao arrearsRecordDao;
    private final WalletService walletService;
    private final AlarmService alarmService;
    private final UserMessageService messageService;

    public ArrearsService(ArrearsRecordDao arrearsRecordDao, WalletService walletService,
                          AlarmService alarmService, UserMessageService messageService) {
        this.arrearsRecordDao = arrearsRecordDao;
        this.walletService = walletService;
        this.alarmService = alarmService;
        this.messageService = messageService;
    }

    /** 计费不足额落单（幂等：同订单累加，重复调用不重复计欠） */
    public void recordShortfall(Long userId, Long orderId, String orderNo, int shortfallFen) {
        if (shortfallFen <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        ArrearsRecordEntity existing = byOrderId(orderId);
        if (existing == null) {
            ArrearsRecordEntity record = new ArrearsRecordEntity();
            record.setUserId(userId);
            record.setOrderId(orderId);
            record.setOrderNo(orderNo);
            record.setAmountFen(shortfallFen);
            record.setSettledFen(0);
            record.setStatus(1);
            record.setCreateTime(now);
            try {
                arrearsRecordDao.insert(record);
            } catch (DuplicateKeyException e) {
                ArrearsRecordEntity raced = byOrderId(orderId);
                if (raced == null) {
                    throw new RRException("欠费单并发写入异常: " + orderId);
                }
                accumulate(raced, shortfallFen);
            }
        } else {
            accumulate(existing, shortfallFen);
        }
        messageService.send(userId, "ARREARS", "欠费提醒",
                "订单 " + orderNo + " 超时费不足，欠费 " + shortfallFen + " 分，请及时补缴");
        log.warn("[欠费] 产生欠费 userId={} orderNo={} amount={}", userId, orderNo, shortfallFen);
    }

    /** 下单门槛：存在未结清欠费 */
    public boolean hasOpenArrears(Long userId) {
        Long count = arrearsRecordDao.selectCount(new LambdaQueryWrapper<ArrearsRecordEntity>()
                .eq(ArrearsRecordEntity::getUserId, userId)
                .eq(ArrearsRecordEntity::getStatus, 1));
        return count != null && count > 0;
    }

    public List<ArrearsRecordEntity> listOpen(Long userId) {
        return arrearsRecordDao.selectList(new LambdaQueryWrapper<ArrearsRecordEntity>()
                .eq(ArrearsRecordEntity::getUserId, userId)
                .orderByDesc(ArrearsRecordEntity::getId));
    }

    public List<ArrearsRecordEntity> listForAdmin(Integer status) {
        return arrearsRecordDao.selectList(new LambdaQueryWrapper<ArrearsRecordEntity>()
                .eq(status != null, ArrearsRecordEntity::getStatus, status)
                .orderByDesc(ArrearsRecordEntity::getId)
                .last("LIMIT 200"));
    }

    /** 余额补缴：扣款与结清同事务（扣款失败整体回滚） */
    @Transactional
    public ArrearsRecordEntity pay(Long userId, Long arrearsId) {
        ArrearsRecordEntity record = require(arrearsId);
        if (!record.getUserId().equals(userId)) {
            throw new RRException("欠费单不存在或无权访问: " + arrearsId);
        }
        if (record.getStatus() != null && record.getStatus() == 2) {
            return record;
        }
        int remaining = (record.getAmountFen() == null ? 0 : record.getAmountFen())
                - (record.getSettledFen() == null ? 0 : record.getSettledFen());
        if (remaining <= 0) {
            return record;
        }
        if (!walletService.deductBalance(userId, remaining)) {
            throw new RRException("余额不足（需 " + remaining + " 分），请先充值");
        }
        settle(record, remaining, "补缴");
        messageService.send(userId, "ARREARS", "欠费已结清",
                "订单 " + record.getOrderNo() + " 欠费 " + remaining + " 分已结清");
        return arrearsRecordDao.selectById(record.getId());
    }

    /** 客服减免（管理端）：全额核销并留痕（操作人由调用方审计） */
    @Transactional
    public ArrearsRecordEntity waive(Long arrearsId, String remark) {
        ArrearsRecordEntity record = require(arrearsId);
        if (record.getStatus() != null && record.getStatus() == 2) {
            return record;
        }
        int remaining = (record.getAmountFen() == null ? 0 : record.getAmountFen())
                - (record.getSettledFen() == null ? 0 : record.getSettledFen());
        settle(record, Math.max(remaining, 0), "减免:" + (remark == null ? "-" : remark));
        messageService.send(record.getUserId(), "ARREARS", "欠费已减免",
                "订单 " + record.getOrderNo() + " 欠费已由客服核销");
        log.warn("[欠费] 减免 arrearsId={} orderNo={} amount={} remark={}",
                arrearsId, record.getOrderNo(), remaining, remark);
        return arrearsRecordDao.selectById(record.getId());
    }

    // ---------- 内部 ----------

    private ArrearsRecordEntity require(Long id) {
        ArrearsRecordEntity record = arrearsRecordDao.selectById(id);
        if (record == null) {
            throw new RRException("欠费单不存在: " + id);
        }
        return record;
    }

    private ArrearsRecordEntity byOrderId(Long orderId) {
        return arrearsRecordDao.selectOne(new LambdaQueryWrapper<ArrearsRecordEntity>()
                .eq(ArrearsRecordEntity::getOrderId, orderId));
    }

    private ArrearsRecordEntity existingOrderId(Long userId, Long orderId) {
        ArrearsRecordEntity raced = byOrderId(orderId);
        if (raced == null) {
            throw new RRException("欠费单并发写入异常: " + orderId);
        }
        return raced;
    }

    private void accumulate(ArrearsRecordEntity record, int shortfallFen) {
        arrearsRecordDao.update(null, new LambdaUpdateWrapper<ArrearsRecordEntity>()
                .eq(ArrearsRecordEntity::getId, record.getId())
                .setSql("amount_fen = amount_fen + " + shortfallFen)
                .set(ArrearsRecordEntity::getStatus, 1)
                .set(ArrearsRecordEntity::getSettleTime, null));
    }

    private void settle(ArrearsRecordEntity record, int amount, String via) {
        long now = System.currentTimeMillis();
        int rows = arrearsRecordDao.update(null, new LambdaUpdateWrapper<ArrearsRecordEntity>()
                .eq(ArrearsRecordEntity::getId, record.getId())
                .eq(ArrearsRecordEntity::getStatus, 1)
                .set(ArrearsRecordEntity::getSettledFen, record.getAmountFen())
                .set(ArrearsRecordEntity::getStatus, 2)
                .set(ArrearsRecordEntity::getSettleTime, now));
        if (rows == 0) {
            throw new RRException("欠费单状态已变更，请刷新重试: " + record.getId());
        }
        alarmService.markRecovered(AlarmService.DEVICE_ORDER, record.getOrderNo(), AlarmType.ORDER_ARREARS);
        log.info("[欠费] 结清 ({}) arrearsId={} orderNo={} amount={}", via, record.getId(), record.getOrderNo(), amount);
    }
}
