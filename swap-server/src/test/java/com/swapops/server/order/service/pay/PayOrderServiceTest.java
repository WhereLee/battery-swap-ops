package com.swapops.server.order.service.pay;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.config.PayProperties;
import com.swapops.server.order.dao.PayOrderDao;
import com.swapops.server.order.entity.PayOrderEntity;
import com.swapops.server.order.enums.PayOrderStatus;
import com.swapops.server.order.enums.PaymentType;
import com.swapops.server.order.service.PaymentRecordService;
import com.swapops.server.user.service.WalletService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 充值支付单单测（S3.4）：创建/成功回调入账/重复回调幂等/坏签名/失败关闭/CAS 冲突。
 */
@DisplayName("充值支付单（回调幂等）")
@ExtendWith(MockitoExtension.class)
class PayOrderServiceTest {

    @Mock
    private PayOrderDao payOrderDao;
    @Mock
    private WalletService walletService;
    @Mock
    private PaymentRecordService paymentRecordService;
    @Mock
    private PaySignatureService paySignatureService;

    private PayProperties payProperties;
    private PayOrderService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PayOrderEntity.class);
    }

    @BeforeEach
    void setUp() {
        payProperties = new PayProperties();
        payProperties.setSecret("0123456789abcdef0123456789abcdef");
        service = new PayOrderService(payOrderDao, walletService, paymentRecordService,
                paySignatureService, payProperties);
    }

    private PayOrderEntity order(String tradeNo, String status, int amountFen) {
        PayOrderEntity order = new PayOrderEntity();
        order.setId(1L);
        order.setTradeNo(tradeNo);
        order.setUserId(7L);
        order.setAmountFen(amountFen);
        order.setPurpose("RECHARGE");
        order.setStatus(status);
        order.setChannel("MOCK");
        order.setCreateTime(1000L);
        order.setUpdateTime(1000L);
        return order;
    }

    @Test
    @DisplayName("创建充值单：WAIT + 金额校验 + 视图含支付地址")
    void 创建充值单() {
        PayOrderEntity created = service.createRecharge(7L, 1000);

        assertThat(created.getStatus()).isEqualTo(PayOrderStatus.WAIT.name());
        assertThat(created.getTradeNo()).startsWith("R");
        assertThat(service.view(created)).containsEntry("amountFen", 1000)
                .containsEntry("payUrl", payProperties.getMockPageBaseUrl() + "/page/" + created.getTradeNo());
        verify(payOrderDao).insert(any(PayOrderEntity.class));
    }

    @Test
    @DisplayName("金额非法：0/超上限拒绝")
    void 金额非法拒绝() {
        assertThatThrownBy(() -> service.createRecharge(7L, 0)).hasMessageContaining("金额非法");
        assertThatThrownBy(() -> service.createRecharge(7L, payProperties.getMaxRechargeFen() + 1))
                .hasMessageContaining("金额非法");
        verifyNoInteractions(payOrderDao);
    }

    @Test
    @DisplayName("成功回调：CAS 命中才入账 + 记 RECHARGE 流水")
    void 成功回调入账() {
        when(paySignatureService.verify("R1", "SUCCESS", "sign-1")).thenReturn(true);
        when(payOrderDao.selectOne(any())).thenReturn(order("R1", PayOrderStatus.WAIT.name(), 1000));
        when(payOrderDao.update(isNull(), any())).thenReturn(1);

        PayOrderEntity result = service.handleCallback("R1", "SUCCESS", "sign-1");

        assertThat(result.getStatus()).isEqualTo(PayOrderStatus.SUCCESS.name());
        verify(walletService).addBalance(7L, 1000);
        verify(paymentRecordService).record(7L, null, PaymentType.RECHARGE, 1000, "充值:R1");
    }

    @Test
    @DisplayName("重复回调：已 SUCCESS 幂等返回，不重复入账")
    void 重复回调幂等() {
        when(paySignatureService.verify("R1", "SUCCESS", "sign-1")).thenReturn(true);
        when(payOrderDao.selectOne(any())).thenReturn(order("R1", PayOrderStatus.SUCCESS.name(), 1000));

        PayOrderEntity result = service.handleCallback("R1", "SUCCESS", "sign-1");

        assertThat(result.getStatus()).isEqualTo(PayOrderStatus.SUCCESS.name());
        verify(payOrderDao, never()).update(isNull(), any());
        verifyNoInteractions(walletService, paymentRecordService);
    }

    @Test
    @DisplayName("坏签名：拒绝且不触达资金")
    void 坏签名拒绝() {
        when(paySignatureService.verify("R1", "SUCCESS", "bad")).thenReturn(false);

        assertThatThrownBy(() -> service.handleCallback("R1", "SUCCESS", "bad"))
                .hasMessageContaining("签名非法");
        verifyNoInteractions(payOrderDao, walletService, paymentRecordService);
    }

    @Test
    @DisplayName("支付失败：WAIT→CLOSED，不入账")
    void 失败关闭() {
        when(paySignatureService.verify("R1", "FAIL", "sign-1")).thenReturn(true);
        when(payOrderDao.selectOne(any())).thenReturn(order("R1", PayOrderStatus.WAIT.name(), 1000));
        when(payOrderDao.update(isNull(), any())).thenReturn(1);

        PayOrderEntity result = service.handleCallback("R1", "FAIL", "sign-1");

        assertThat(result.getStatus()).isEqualTo(PayOrderStatus.CLOSED.name());
        verifyNoInteractions(walletService, paymentRecordService);
    }

    @Test
    @DisplayName("CAS 未命中且现态非 SUCCESS：状态冲突拒绝")
    void 状态冲突拒绝() {
        when(paySignatureService.verify("R1", "SUCCESS", "sign-1")).thenReturn(true);
        when(payOrderDao.selectOne(any())).thenReturn(order("R1", PayOrderStatus.WAIT.name(), 1000));
        when(payOrderDao.update(isNull(), any())).thenReturn(0);
        when(payOrderDao.selectById(1L)).thenReturn(order("R1", PayOrderStatus.CLOSED.name(), 1000));

        assertThatThrownBy(() -> service.handleCallback("R1", "SUCCESS", "sign-1"))
                .hasMessageContaining("状态冲突");
        verifyNoInteractions(walletService, paymentRecordService);
    }
}
