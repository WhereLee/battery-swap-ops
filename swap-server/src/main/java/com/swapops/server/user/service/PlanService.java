package com.swapops.server.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.common.RRException;
import com.swapops.server.common.cache.CacheKeys;
import com.swapops.server.common.cache.TwoLevelCacheService;
import com.swapops.server.order.enums.PaymentType;
import com.swapops.server.order.service.PaymentRecordService;
import com.swapops.server.user.dao.PlanDao;
import com.swapops.server.user.dao.UserPlanDao;
import com.swapops.server.user.entity.PlanEntity;
import com.swapops.server.user.entity.UserPlanEntity;
import com.swapops.server.user.enums.PlanType;
import com.swapops.server.user.enums.UserPlanStatus;
import com.swapops.server.user.form.PlanAdminForm;
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
    private final TwoLevelCacheService cache;

    public PlanService(PlanDao planDao, UserPlanDao userPlanDao, WalletService walletService,
                       PaymentRecordService paymentRecordService, TwoLevelCacheService cache) {
        this.planDao = planDao;
        this.userPlanDao = userPlanDao;
        this.walletService = walletService;
        this.paymentRecordService = paymentRecordService;
        this.cache = cache;
    }

    /** 生效套餐目录（低频变更的字典读；S4 套餐 CRUD 变更后须 evict PLAN_ACTIVE_LIST） */
    public List<PlanEntity> listActive() {
        return cache.getList(CacheKeys.PLAN_ACTIVE_LIST, PlanEntity.class,
                () -> planDao.selectList(new LambdaQueryWrapper<PlanEntity>()
                        .eq(PlanEntity::getStatus, 1)
                        .orderByAsc(PlanEntity::getPriceFen)));
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

    // ---------- 管理端 CRUD（S4.5 批二：写路径一律 evict 目录缓存） ----------

    /** 全量套餐（含下架；管理端不做缓存） */
    public List<PlanEntity> adminList() {
        return planDao.selectList(new LambdaQueryWrapper<PlanEntity>()
                .orderByAsc(PlanEntity::getId));
    }

    public PlanEntity createPlan(PlanAdminForm form) {
        validatePlanForm(form);
        PlanEntity plan = new PlanEntity();
        applyForm(plan, form);
        plan.setStatus(1);
        plan.setCreateTime(System.currentTimeMillis());
        planDao.insert(plan);
        cache.evict(CacheKeys.PLAN_ACTIVE_LIST);
        log.info("套餐创建 id={} name={} type={} price={}", plan.getId(), plan.getName(),
                plan.getPlanType(), plan.getPriceFen());
        return plan;
    }

    public PlanEntity updatePlan(Long id, PlanAdminForm form) {
        PlanEntity plan = requirePlan(id);
        validatePlanForm(form);
        applyForm(plan, form);
        planDao.updateById(plan);
        cache.evict(CacheKeys.PLAN_ACTIVE_LIST);
        log.info("套餐更新 id={} name={} price={} status={}", plan.getId(), plan.getName(),
                plan.getPriceFen(), plan.getStatus());
        return planDao.selectById(id);
    }

    /** 上下架：1 上架 / 2 下架 */
    public PlanEntity changePlanStatus(Long id, Integer status) {
        if (status == null || (status != 1 && status != 2)) {
            throw new RRException("套餐状态可选 1 上架 / 2 下架");
        }
        PlanEntity plan = requirePlan(id);
        plan.setStatus(status);
        planDao.updateById(plan);
        cache.evict(CacheKeys.PLAN_ACTIVE_LIST);
        log.info("套餐上下架 id={} name={} status={}", id, plan.getName(), status);
        return planDao.selectById(id);
    }

    /** 删除：已被购买的套餐禁止删除（只允许下架） */
    public void deletePlan(Long id) {
        PlanEntity plan = requirePlan(id);
        Long purchased = userPlanDao.selectCount(new LambdaQueryWrapper<UserPlanEntity>()
                .eq(UserPlanEntity::getPlanId, id));
        if (purchased != null && purchased > 0) {
            throw new RRException("套餐已被购买（" + purchased + " 条），不能删除，请改为下架");
        }
        planDao.deleteById(id);
        cache.evict(CacheKeys.PLAN_ACTIVE_LIST);
        log.info("套餐删除 id={} name={}", id, plan.getName());
    }

    private PlanEntity requirePlan(Long id) {
        PlanEntity plan = planDao.selectById(id);
        if (plan == null) {
            throw new RRException("套餐不存在: " + id);
        }
        return plan;
    }

    private void validatePlanForm(PlanAdminForm form) {
        if (form == null || form.getName() == null || form.getName().isBlank()) {
            throw new RRException("套餐名称必填");
        }
        if (form.getName().length() > 32) {
            throw new RRException("套餐名称过长（<=32）");
        }
        PlanType type;
        try {
            type = PlanType.valueOf(form.getPlanType() == null ? "" : form.getPlanType().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RRException("套餐类型非法（TIMES/MONTHLY）: " + form.getPlanType());
        }
        if (form.getPriceFen() == null || form.getPriceFen() < 0) {
            throw new RRException("套餐价格必填且 >=0（分）");
        }
        if (type == PlanType.TIMES && (form.getTotalTimes() == null || form.getTotalTimes() <= 0)) {
            throw new RRException("次卡需填写总次数（>0）");
        }
        if (type == PlanType.MONTHLY && (form.getDurationDays() == null || form.getDurationDays() <= 0)) {
            throw new RRException("月卡需填写有效天数（>0）");
        }
        if (form.getDailyLimitTimes() != null && form.getDailyLimitTimes() <= 0) {
            throw new RRException("日限次需 >0（可空=不限）");
        }
    }

    private void applyForm(PlanEntity plan, PlanAdminForm form) {
        plan.setName(form.getName().trim());
        plan.setPlanType(form.getPlanType().trim().toUpperCase());
        plan.setPriceFen(form.getPriceFen());
        plan.setTotalTimes("TIMES".equals(plan.getPlanType()) ? form.getTotalTimes() : null);
        plan.setDurationDays("MONTHLY".equals(plan.getPlanType()) ? form.getDurationDays() : null);
        plan.setDailyLimitTimes(form.getDailyLimitTimes());
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
