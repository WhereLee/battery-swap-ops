package com.swapops.server.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.common.RRException;
import com.swapops.server.order.enums.PaymentType;
import com.swapops.server.order.service.PaymentRecordService;
import com.swapops.server.user.dao.PlanDao;
import com.swapops.server.user.dao.UserPlanDao;
import com.swapops.server.user.entity.PlanEntity;
import com.swapops.server.user.entity.UserPlanEntity;
import com.swapops.server.user.enums.PlanType;
import com.swapops.server.user.enums.UserPlanStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 套餐：购买（幂等 + 余额扣款 + 支付流水）、生效判定、次卡扣次（CAS）。
 */
@Slf4j
@Service
public class PlanService {

    private static final long TIMES_PLAN_VALIDITY_MILLIS = 365L * 24 * 3600 * 1000;

    private final PlanDao planDao;
    private final UserPlanDao userPlanDao;
    private final WalletService walletService;
    private final PaymentRecordService paymentRecordService;

    public PlanService(PlanDao planDao, UserPlanDao userPlanDao, WalletService walletService,
                       PaymentRecordService paymentRecordService) {
        this.planDao = planDao;
        this.userPlanDao = userPlanDao;
        this.walletService = walletService;
        this.paymentRecordService = paymentRecordService;
    }

    public List<PlanEntity> listActive() {
        return planDao.selectList(new LambdaQueryWrapper<PlanEntity>()
                .eq(PlanEntity::getStatus, 1)
                .orderByAsc(PlanEntity::getPriceFen));
    }

    /**
     * 购买套餐：先落 user_plan（idem_key 唯一=幂等闸）再扣余额——扣款失败整体回滚。
     */
    @Transactional
    public UserPlanEntity purchase(Long userId, Long planId, String idemKey) {
        UserPlanEntity existing = byIdemKey(idemKey);
        if (existing != null) {
            return existing;
        }
        PlanEntity plan = planDao.selectById(planId);
        if (plan == null || plan.getStatus() == null || plan.getStatus() != 1) {
            throw new RRException("套餐不存在或已下架: " + planId);
        }
        long now = System.currentTimeMillis();
        UserPlanEntity userPlan = new UserPlanEntity();
        userPlan.setUserId(userId);
        userPlan.setPlanId(planId);
        userPlan.setStartTime(now);
        if (PlanType.MONTHLY.name().equals(plan.getPlanType())) {
            int days = plan.getDurationDays() == null ? 30 : plan.getDurationDays();
            userPlan.setEndTime(now + days * 24L * 3600 * 1000);
            userPlan.setRemainingTimes(null);
        } else {
            userPlan.setEndTime(now + TIMES_PLAN_VALIDITY_MILLIS);
            userPlan.setRemainingTimes(plan.getTotalTimes());
        }
        userPlan.setStatus(UserPlanStatus.ACTIVE.getCode());
        userPlan.setIdemKey(idemKey);
        userPlan.setCreateTime(now);
        userPlan.setUpdateTime(now);
        try {
            userPlanDao.insert(userPlan);
        } catch (DuplicateKeyException e) {
            // 并发同 key：返回已存在的那笔
            UserPlanEntity raced = byIdemKey(idemKey);
            if (raced != null) {
                return raced;
            }
            throw e;
        }
        int price = plan.getPriceFen() == null ? 0 : plan.getPriceFen();
        if (price > 0 && !walletService.deductBalance(userId, price)) {
            throw new RRException("余额不足，购买需 " + price + " 分");
        }
        paymentRecordService.record(userId, null, PaymentType.PLAN_PURCHASE, price, "购买套餐:" + plan.getName());
        log.info("套餐购买成功 userId={} planId={} userPlanId={}", userId, planId, userPlan.getId());
        return userPlan;
    }

    private UserPlanEntity byIdemKey(String idemKey) {
        return userPlanDao.selectOne(new LambdaQueryWrapper<UserPlanEntity>()
                .eq(UserPlanEntity::getIdemKey, idemKey));
    }

    /** 生效可用套餐（TIMES 需剩余次数>0；MONTHLY 需在有效期内） */
    public UserPlanEntity findUsablePlan(Long userId, long now) {
        UserPlanEntity userPlan = userPlanDao.selectOne(new LambdaQueryWrapper<UserPlanEntity>()
                .eq(UserPlanEntity::getUserId, userId)
                .eq(UserPlanEntity::getStatus, UserPlanStatus.ACTIVE.getCode())
                .ge(UserPlanEntity::getEndTime, now)
                .orderByDesc(UserPlanEntity::getId)
                .last("LIMIT 1"));
        if (userPlan == null) {
            return null;
        }
        if (userPlan.getRemainingTimes() != null && userPlan.getRemainingTimes() <= 0) {
            return null;
        }
        return userPlan;
    }

    /**
     * 次卡扣次（CAS）：扣到 0 自动置"用完"；并发扣次不会扣穿。
     *
     * @return true=扣次成功
     */
    public boolean deductTimes(Long userPlanId) {
        int rows = userPlanDao.update(null, new LambdaUpdateWrapper<UserPlanEntity>()
                .eq(UserPlanEntity::getId, userPlanId)
                .eq(UserPlanEntity::getStatus, UserPlanStatus.ACTIVE.getCode())
                .ge(UserPlanEntity::getRemainingTimes, 1)
                .setSql("remaining_times = remaining_times - 1")
                .setSql("status = IF(remaining_times <= 0, "
                        + UserPlanStatus.USED_UP.getCode() + ", " + UserPlanStatus.ACTIVE.getCode() + ")")
                .set(UserPlanEntity::getUpdateTime, System.currentTimeMillis()));
        return rows > 0;
    }
}
