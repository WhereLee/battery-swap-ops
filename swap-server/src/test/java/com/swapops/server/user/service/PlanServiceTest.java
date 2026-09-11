package com.swapops.server.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.common.RRException;
import com.swapops.server.common.cache.TwoLevelCacheService;
import com.swapops.server.order.enums.PaymentType;
import com.swapops.server.order.service.PaymentRecordService;
import com.swapops.server.user.dao.PlanDao;
import com.swapops.server.user.dao.UserPlanDao;
import com.swapops.server.user.entity.PlanEntity;
import com.swapops.server.user.entity.UserPlanEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 套餐单测：购买幂等（idem_key 重放不重复扣款）、余额不足回滚、次卡 CAS 扣次。
 */
@DisplayName("套餐（购买幂等 + 扣次）")
@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock
    private PlanDao planDao;
    @Mock
    private UserPlanDao userPlanDao;
    @Mock
    private WalletService walletService;
    @Mock
    private PaymentRecordService paymentRecordService;
    @Mock
    private TwoLevelCacheService cache;
    @InjectMocks
    private PlanService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, UserPlanEntity.class);
    }

    private PlanEntity plan() {
        PlanEntity plan = new PlanEntity();
        plan.setId(100L);
        plan.setName("次卡10次");
        plan.setPlanType("TIMES");
        plan.setPriceFen(3000);
        plan.setTotalTimes(10);
        plan.setStatus(1);
        return plan;
    }

    @Test
    @DisplayName("购买成功：落 user_plan → 扣余额 → 记支付流水")
    void 购买成功() {
        when(userPlanDao.selectOne(any())).thenReturn(null);
        when(planDao.selectById(100L)).thenReturn(plan());
        when(userPlanDao.insert(any(UserPlanEntity.class))).thenReturn(1);
        when(walletService.deductBalance(7L, 3000)).thenReturn(true);

        service.purchase(7L, 100L, "idem-1");

        verify(userPlanDao).insert(any(UserPlanEntity.class));
        verify(walletService).deductBalance(7L, 3000);
        verify(paymentRecordService).record(eq(7L), isNull(), eq(PaymentType.PLAN_PURCHASE), eq(3000), anyString());
    }

    @Test
    @DisplayName("幂等重放：同 idem_key 直接返回已购套餐，不重复扣款")
    void 幂等重放() {
        UserPlanEntity existing = new UserPlanEntity();
        existing.setId(55L);
        existing.setIdemKey("idem-1");
        when(userPlanDao.selectOne(any())).thenReturn(existing);

        UserPlanEntity result = service.purchase(7L, 100L, "idem-1");

        assertThat(result.getId()).isEqualTo(55L);
        verifyNoInteractions(planDao, walletService, paymentRecordService);
    }

    @Test
    @DisplayName("余额不足：抛业务异常（事务回滚 user_plan）")
    void 余额不足回滚() {
        when(userPlanDao.selectOne(any())).thenReturn(null);
        when(planDao.selectById(100L)).thenReturn(plan());
        when(userPlanDao.insert(any(UserPlanEntity.class))).thenReturn(1);
        when(walletService.deductBalance(7L, 3000)).thenReturn(false);

        assertThatThrownBy(() -> service.purchase(7L, 100L, "idem-2"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("余额不足");
        verify(paymentRecordService, never()).record(any(), any(), any(), eq(3000), anyString());
    }

    @Test
    @DisplayName("套餐下架：拒绝购买")
    void 套餐下架拒绝() {
        when(userPlanDao.selectOne(any())).thenReturn(null);
        PlanEntity off = plan();
        off.setStatus(2);
        when(planDao.selectById(100L)).thenReturn(off);

        assertThatThrownBy(() -> service.purchase(7L, 100L, "idem-3"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("下架");
    }

    @Test
    @DisplayName("次卡扣次：CAS 命中=成功，扣穿=失败")
    void 扣次CAS() {
        when(userPlanDao.update(isNull(), any())).thenReturn(1);
        assertThat(service.deductTimes(55L)).isTrue();

        when(userPlanDao.update(isNull(), any())).thenReturn(0);
        assertThat(service.deductTimes(55L)).isFalse();
    }
}
