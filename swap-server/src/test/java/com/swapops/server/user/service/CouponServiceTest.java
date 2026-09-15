package com.swapops.server.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.common.RRException;
import com.swapops.server.user.dao.CouponTemplateDao;
import com.swapops.server.user.dao.UserCouponDao;
import com.swapops.server.user.entity.CouponTemplateEntity;
import com.swapops.server.user.entity.UserCouponEntity;
import com.swapops.server.user.enums.UserCouponStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 优惠券单测（S7 WP-D）：模板校验 / 发放（限领+发行量 CAS+快照）/ 锁定边界 / 核销 / 释放。
 */
@DisplayName("优惠券")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponServiceTest {

    @Mock
    private CouponTemplateDao couponTemplateDao;
    @Mock
    private UserCouponDao userCouponDao;
    @Mock
    private UserMessageService messageService;

    private CouponService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CouponTemplateEntity.class);
        TableInfoHelper.initTableInfo(assistant, UserCouponEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new CouponService(couponTemplateDao, userCouponDao, messageService);
    }

    private CouponTemplateEntity template() {
        CouponTemplateEntity template = new CouponTemplateEntity();
        template.setId(5L);
        template.setName("新客立减1元");
        template.setValueFen(100);
        template.setMinAmountFen(100);
        template.setTotalQuantity(100);
        template.setIssuedCount(0);
        template.setPerUserLimit(1);
        template.setValidTo(System.currentTimeMillis() + 86_400_000L);
        template.setStatus(1);
        return template;
    }

    private UserCouponEntity coupon(int status) {
        UserCouponEntity coupon = new UserCouponEntity();
        coupon.setId(9L);
        coupon.setUserId(7L);
        coupon.setTemplateId(5L);
        coupon.setStatus(status);
        coupon.setValueFen(100);
        coupon.setMinAmountFen(100);
        coupon.setExpireTime(System.currentTimeMillis() + 86_400_000L);
        return coupon;
    }

    @Test
    @DisplayName("模板：非法参数拒绝；成功插入")
    void 模板校验() {
        assertThatThrownBy(() -> service.createTemplate("", 100, 0, 10, 1, 30))
                .isInstanceOf(RRException.class);
        assertThatThrownBy(() -> service.createTemplate("x", 0, 0, 10, 1, 30))
                .isInstanceOf(RRException.class).hasMessageContaining("面额");
        assertThatThrownBy(() -> service.createTemplate("x", 100, 0, 0, 1, 30))
                .isInstanceOf(RRException.class).hasMessageContaining("发行量");
        assertThatThrownBy(() -> service.createTemplate("x", 100, 0, 10, 1, 0))
                .isInstanceOf(RRException.class).hasMessageContaining("有效期");

        when(couponTemplateDao.selectOne(any())).thenReturn(null);
        when(couponTemplateDao.insert(any(CouponTemplateEntity.class))).thenReturn(1);
        CouponTemplateEntity created = service.createTemplate("新客立减1元", 100, 100, 10, 1, 30);
        assertThat(created.getType()).isEqualTo("FIXED");
        assertThat(created.getIssuedCount()).isZero();
    }

    @Test
    @DisplayName("发放：限额内发放（CAS 扣发行量 + 快照 + 站内信）；超限跳过；发完拒绝")
    void 发放() {
        when(couponTemplateDao.selectById(5L)).thenReturn(template());
        when(userCouponDao.selectCount(any())).thenReturn(0L);
        when(couponTemplateDao.update(isNull(), any())).thenReturn(1);
        when(userCouponDao.insert(any(UserCouponEntity.class))).thenReturn(1);

        int granted = service.grant(5L, List.of(7L));
        assertThat(granted).isEqualTo(1);
        ArgumentCaptor<UserCouponEntity> captor = ArgumentCaptor.forClass(UserCouponEntity.class);
        verify(userCouponDao).insert(captor.capture());
        assertThat(captor.getValue().getValueFen()).isEqualTo(100);
        assertThat(captor.getValue().getStatus()).isEqualTo(UserCouponStatus.UNUSED.getCode());
        verify(messageService).send(eq(7L), eq("REWARD"), any(), any());

        // 超限跳过
        when(userCouponDao.selectCount(any())).thenReturn(1L);
        assertThat(service.grant(5L, List.of(7L))).isZero();

        // 发行量发完：CAS 未命中 → 拒绝
        when(userCouponDao.selectCount(any())).thenReturn(0L);
        when(couponTemplateDao.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.grant(5L, List.of(8L)))
                .isInstanceOf(RRException.class).hasMessageContaining("发完");
    }

    @Test
    @DisplayName("锁定：成功 CAS；过期/非本人/门槛不足/并发占用拒绝")
    void 锁定() {
        when(userCouponDao.update(isNull(), any())).thenReturn(1);
        when(userCouponDao.selectById(9L)).thenReturn(coupon(UserCouponStatus.UNUSED.getCode()));
        assertThat(service.lockForOrder(7L, 9L, 99L, 300)).isNotNull();
        verify(userCouponDao, org.mockito.Mockito.atLeastOnce()).update(isNull(), any());

        UserCouponEntity expired = coupon(UserCouponStatus.UNUSED.getCode());
        expired.setExpireTime(System.currentTimeMillis() - 1000);
        when(userCouponDao.selectById(9L)).thenReturn(expired);
        assertThatThrownBy(() -> service.lockForOrder(7L, 9L, 99L, 300))
                .isInstanceOf(RRException.class).hasMessageContaining("过期");

        UserCouponEntity other = coupon(UserCouponStatus.UNUSED.getCode());
        other.setUserId(8L);
        when(userCouponDao.selectById(9L)).thenReturn(other);
        assertThatThrownBy(() -> service.lockForOrder(7L, 9L, 99L, 300))
                .isInstanceOf(RRException.class).hasMessageContaining("无权");

        when(userCouponDao.selectById(9L)).thenReturn(coupon(UserCouponStatus.UNUSED.getCode()));
        assertThatThrownBy(() -> service.lockForOrder(7L, 9L, 99L, 50))
                .isInstanceOf(RRException.class).hasMessageContaining("门槛");

        when(userCouponDao.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.lockForOrder(7L, 9L, 99L, 300))
                .isInstanceOf(RRException.class).hasMessageContaining("并发");
    }

    @Test
    @DisplayName("核销：本单 LOCKED → USED，抵扣不超过基础费；未锁定返回 0")
    void 核销() {
        when(userCouponDao.selectOne(any())).thenReturn(coupon(UserCouponStatus.LOCKED.getCode()));
        when(userCouponDao.update(isNull(), any())).thenReturn(1);
        assertThat(service.consumeForCharge(99L, 9L, 300)).isEqualTo(100);
        assertThat(service.consumeForCharge(99L, 9L, 50)).isEqualTo(50); // min(value, baseFee)

        when(userCouponDao.selectOne(any())).thenReturn(null);
        assertThat(service.consumeForCharge(99L, 9L, 300)).isZero();
        assertThat(service.consumeForCharge(99L, null, 300)).isZero();
    }

    @Test
    @DisplayName("释放：LOCKED→UNUSED（订单终态钩子）")
    void 释放() {
        when(userCouponDao.update(isNull(), any())).thenReturn(1);
        assertThat(service.releaseLocked(99L)).isTrue();
        when(userCouponDao.update(isNull(), any())).thenReturn(0);
        assertThat(service.releaseLocked(99L)).isFalse();
        verify(userCouponDao, never()).selectOne(any());
    }
}
