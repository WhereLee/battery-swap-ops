package com.swapops.server.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.user.dao.WalletDao;
import com.swapops.server.user.entity.WalletEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钱包单测：扣款/转押金 CAS 语义、押金退款防双退。
 */
@DisplayName("钱包（CAS 资金操作）")
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletDao walletDao;
    @InjectMocks
    private WalletService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WalletEntity.class);
    }

    @Test
    @DisplayName("扣余额：条件更新命中=成功；未命中=余额不足")
    void 扣余额分支() {
        when(walletDao.update(isNull(), any())).thenReturn(1);
        assertThat(service.deductBalance(7L, 300)).isTrue();

        when(walletDao.update(isNull(), any())).thenReturn(0);
        assertThat(service.deductBalance(7L, 300)).isFalse();
    }

    @Test
    @DisplayName("余额转押金：原子两列条件更新")
    void 余额转押金() {
        when(walletDao.update(isNull(), any())).thenReturn(1);
        assertThat(service.moveBalanceToDeposit(7L, 9900)).isTrue();
    }

    @Test
    @DisplayName("退押金：按读到的押金值 CAS，防并发双退")
    void 退押金() {
        WalletEntity wallet = new WalletEntity();
        wallet.setUserId(7L);
        wallet.setDepositFen(9900);
        when(walletDao.selectOne(any())).thenReturn(wallet);
        when(walletDao.update(isNull(), any())).thenReturn(1);

        assertThat(service.refundDeposit(7L)).isEqualTo(9900);
    }

    @Test
    @DisplayName("退押金：无押金/并发未命中 → 0，无资金动作")
    void 退押金边界() {
        WalletEntity zero = new WalletEntity();
        zero.setDepositFen(0);
        when(walletDao.selectOne(any())).thenReturn(zero);
        assertThat(service.refundDeposit(7L)).isZero();
        verify(walletDao, never()).update(isNull(), any());

        WalletEntity wallet = new WalletEntity();
        wallet.setDepositFen(9900);
        when(walletDao.selectOne(any())).thenReturn(wallet);
        when(walletDao.update(isNull(), any())).thenReturn(0);
        assertThat(service.refundDeposit(7L)).isZero();
    }
}
