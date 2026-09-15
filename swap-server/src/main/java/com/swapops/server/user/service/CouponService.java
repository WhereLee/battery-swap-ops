package com.swapops.server.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.common.RRException;
import com.swapops.server.user.dao.CouponTemplateDao;
import com.swapops.server.user.dao.UserCouponDao;
import com.swapops.server.user.entity.CouponTemplateEntity;
import com.swapops.server.user.entity.UserCouponEntity;
import com.swapops.server.user.enums.UserCouponStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 优惠券（S7 WP-D）：模板（FIXED 立减）→ 发放（发行量 CAS + 每人限领 + 面额快照）→
 * 下单锁定（UNUSED→LOCKED）→ 计费核销（LOCKED→USED）→ 终态释放（LOCKED→UNUSED，订单终态统一钩子）。
 * 到期惰性置 EXPIRED（领券时快照 expire_time）。
 */
@Slf4j
@Service
public class CouponService {

    private final CouponTemplateDao couponTemplateDao;
    private final UserCouponDao userCouponDao;
    private final UserMessageService messageService;

    public CouponService(CouponTemplateDao couponTemplateDao, UserCouponDao userCouponDao,
                         UserMessageService messageService) {
        this.couponTemplateDao = couponTemplateDao;
        this.userCouponDao = userCouponDao;
        this.messageService = messageService;
    }

    // ---------- 管理端 ----------

    public CouponTemplateEntity createTemplate(String name, Integer valueFen, Integer minAmountFen,
                                               Integer totalQuantity, Integer perUserLimit, Integer validDays) {
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new RRException("券名称必填且 <=64 字");
        }
        if (couponTemplateDao.selectOne(new LambdaQueryWrapper<CouponTemplateEntity>()
                .eq(CouponTemplateEntity::getName, name.trim())) != null) {
            throw new RRException("券名称已存在: " + name);
        }
        if (valueFen == null || valueFen <= 0) {
            throw new RRException("面额必需 >0（分）");
        }
        if (minAmountFen == null || minAmountFen < 0) {
            throw new RRException("门槛必需 >=0（分）");
        }
        if (totalQuantity == null || totalQuantity <= 0) {
            throw new RRException("发行量必需 >0");
        }
        if (perUserLimit == null || perUserLimit <= 0) {
            throw new RRException("每人限领必需 >0");
        }
        if (validDays == null || validDays <= 0) {
            throw new RRException("有效期天数必需 >0");
        }
        long now = System.currentTimeMillis();
        CouponTemplateEntity template = new CouponTemplateEntity();
        template.setName(name.trim());
        template.setType("FIXED");
        template.setValueFen(valueFen);
        template.setMinAmountFen(minAmountFen);
        template.setTotalQuantity(totalQuantity);
        template.setIssuedCount(0);
        template.setPerUserLimit(perUserLimit);
        template.setValidFrom(now);
        template.setValidTo(now + validDays * 24L * 3600 * 1000);
        template.setStatus(1);
        template.setCreateTime(now);
        couponTemplateDao.insert(template);
        log.info("[券] 模板创建 id={} name={} value={} total={}", template.getId(), template.getName(),
                valueFen, totalQuantity);
        return template;
    }

    public List<CouponTemplateEntity> listTemplates() {
        return couponTemplateDao.selectList(new LambdaQueryWrapper<CouponTemplateEntity>()
                .orderByDesc(CouponTemplateEntity::getId)
                .last("LIMIT 200"));
    }

    /** 发放（幂等跳过超限/停用）：发行量 CAS 防超发 */
    @Transactional
    public int grant(Long templateId, List<Long> userIds) {
        CouponTemplateEntity template = couponTemplateDao.selectById(templateId);
        if (template == null) {
            throw new RRException("券模板不存在: " + templateId);
        }
        if (template.getStatus() == null || template.getStatus() != 1) {
            throw new RRException("券模板已停用: " + templateId);
        }
        long now = System.currentTimeMillis();
        if (template.getValidTo() != null && template.getValidTo() < now) {
            throw new RRException("券模板已过期: " + templateId);
        }
        int granted = 0;
        for (Long userId : userIds) {
            Long held = userCouponDao.selectCount(new LambdaQueryWrapper<UserCouponEntity>()
                    .eq(UserCouponEntity::getUserId, userId)
                    .eq(UserCouponEntity::getTemplateId, templateId)
                    .ne(UserCouponEntity::getStatus, UserCouponStatus.EXPIRED.getCode()));
            if (held != null && held >= template.getPerUserLimit()) {
                log.info("[券] 已达每人限领，跳过 userId={} templateId={}", userId, templateId);
                continue;
            }
            int rows = couponTemplateDao.update(null, new LambdaUpdateWrapper<CouponTemplateEntity>()
                    .eq(CouponTemplateEntity::getId, templateId)
                    .lt(CouponTemplateEntity::getIssuedCount, template.getTotalQuantity())
                    .setSql("issued_count = issued_count + 1"));
            if (rows == 0) {
                throw new RRException("券已发完: " + templateId);
            }
            UserCouponEntity coupon = new UserCouponEntity();
            coupon.setUserId(userId);
            coupon.setTemplateId(templateId);
            coupon.setStatus(UserCouponStatus.UNUSED.getCode());
            coupon.setValueFen(template.getValueFen());
            coupon.setMinAmountFen(template.getMinAmountFen());
            coupon.setReceivedTime(now);
            coupon.setExpireTime(template.getValidTo());
            userCouponDao.insert(coupon);
            messageService.send(userId, "REWARD", "获得优惠券",
                    "「" + template.getName() + "」立减 " + template.getValueFen() + " 分，请尽快使用");
            granted++;
        }
        log.info("[券] 发放 templateId={} requests={} granted={}", templateId, userIds.size(), granted);
        return granted;
    }

    // ---------- 用户端 ----------

    /** 我的券（惰性过期：先过期再查询） */
    public List<UserCouponEntity> listUserCoupons(Long userId, Integer status) {
        expireOutdated(userId);
        return userCouponDao.selectList(new LambdaQueryWrapper<UserCouponEntity>()
                .eq(UserCouponEntity::getUserId, userId)
                .eq(status != null, UserCouponEntity::getStatus, status)
                .orderByDesc(UserCouponEntity::getId)
                .last("LIMIT 100"));
    }

    /** 下单锁定：归属/未用/未过期/门槛校验 + CAS UNUSED→LOCKED */
    public UserCouponEntity lockForOrder(Long userId, Long couponId, Long orderId, int baseFeeFen) {
        expireOutdated(userId);
        UserCouponEntity coupon = userCouponDao.selectById(couponId);
        if (coupon == null || !coupon.getUserId().equals(userId)) {
            throw new RRException("优惠券不存在或无权使用: " + couponId);
        }
        if (coupon.getStatus() != UserCouponStatus.UNUSED.getCode()) {
            throw new RRException("优惠券不可用（已使用/锁定/过期）: " + couponId);
        }
        if (coupon.getExpireTime() != null && coupon.getExpireTime() < System.currentTimeMillis()) {
            throw new RRException("优惠券已过期: " + couponId);
        }
        if (coupon.getMinAmountFen() != null && baseFeeFen < coupon.getMinAmountFen()) {
            throw new RRException("订单金额未达优惠券门槛（需 " + coupon.getMinAmountFen() + " 分）");
        }
        long now = System.currentTimeMillis();
        int rows = userCouponDao.update(null, new LambdaUpdateWrapper<UserCouponEntity>()
                .eq(UserCouponEntity::getId, couponId)
                .eq(UserCouponEntity::getUserId, userId)
                .eq(UserCouponEntity::getStatus, UserCouponStatus.UNUSED.getCode())
                .set(UserCouponEntity::getStatus, UserCouponStatus.LOCKED.getCode())
                .set(UserCouponEntity::getLockedOrderId, orderId)
                .set(UserCouponEntity::getLockedTime, now));
        if (rows == 0) {
            throw new RRException("优惠券不可用（并发占用）: " + couponId);
        }
        return userCouponDao.selectById(couponId);
    }

    /** 计费核销：仅本单 LOCKED 可核销；返回实际抵扣（不超过 baseFee） */
    public int consumeForCharge(Long orderId, Long couponId, int baseFeeFen) {
        if (couponId == null) {
            return 0;
        }
        UserCouponEntity coupon = userCouponDao.selectOne(new LambdaQueryWrapper<UserCouponEntity>()
                .eq(UserCouponEntity::getLockedOrderId, orderId)
                .eq(UserCouponEntity::getStatus, UserCouponStatus.LOCKED.getCode())
                .last("LIMIT 1"));
        if (coupon == null) {
            log.warn("[券] 核销未命中（未锁定/已释放） orderId={} couponId={}", orderId, couponId);
            return 0;
        }
        int discount = Math.min(coupon.getValueFen() == null ? 0 : coupon.getValueFen(), baseFeeFen);
        int rows = userCouponDao.update(null, new LambdaUpdateWrapper<UserCouponEntity>()
                .eq(UserCouponEntity::getId, coupon.getId())
                .eq(UserCouponEntity::getStatus, UserCouponStatus.LOCKED.getCode())
                .set(UserCouponEntity::getStatus, UserCouponStatus.USED.getCode())
                .set(UserCouponEntity::getUsedOrderId, orderId)
                .set(UserCouponEntity::getUsedTime, System.currentTimeMillis())
                .set(UserCouponEntity::getLockedOrderId, null));
        if (rows == 0) {
            return 0;
        }
        log.info("[券] 核销 couponId={} orderId={} discount={}", coupon.getId(), orderId, discount);
        return discount;
    }

    /** 订单终态统一钩子：LOCKED→UNUSED 释放（幂等；无锁券为 no-op） */
    public boolean releaseLocked(Long orderId) {
        int rows = userCouponDao.update(null, new LambdaUpdateWrapper<UserCouponEntity>()
                .eq(UserCouponEntity::getLockedOrderId, orderId)
                .eq(UserCouponEntity::getStatus, UserCouponStatus.LOCKED.getCode())
                .set(UserCouponEntity::getStatus, UserCouponStatus.UNUSED.getCode())
                .set(UserCouponEntity::getLockedOrderId, null)
                .set(UserCouponEntity::getLockedTime, null));
        if (rows > 0) {
            log.info("[券] 释放锁券 orderId={}", orderId);
        }
        return rows > 0;
    }

    private void expireOutdated(Long userId) {
        userCouponDao.update(null, new LambdaUpdateWrapper<UserCouponEntity>()
                .eq(UserCouponEntity::getUserId, userId)
                .eq(UserCouponEntity::getStatus, UserCouponStatus.UNUSED.getCode())
                .lt(UserCouponEntity::getExpireTime, System.currentTimeMillis())
                .set(UserCouponEntity::getStatus, UserCouponStatus.EXPIRED.getCode()));
    }
}
