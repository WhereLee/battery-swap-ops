package com.swapops.server.order.service.pay;

import com.swapops.server.config.PayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付签名单测（S3.4）：确定性/验签/篡改拒绝/大小写不敏感。
 */
@DisplayName("支付回调签名（HMAC-SHA256）")
class PaySignatureServiceTest {

    private PaySignatureService service;

    @BeforeEach
    void setUp() {
        PayProperties properties = new PayProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        service = new PaySignatureService(properties);
    }

    @Test
    @DisplayName("签名确定性 + 验签通过")
    void 签名与验签() {
        String sign = service.sign("R123", "SUCCESS");

        assertThat(sign).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(service.sign("R123", "SUCCESS")).isEqualTo(sign);
        assertThat(service.verify("R123", "SUCCESS", sign)).isTrue();
        assertThat(service.verify("R123", "SUCCESS", sign.toUpperCase())).isTrue();
    }

    @Test
    @DisplayName("篡改/缺失签名拒绝")
    void 验签拒绝() {
        String sign = service.sign("R123", "SUCCESS");

        assertThat(service.verify("R124", "SUCCESS", sign)).isFalse();
        assertThat(service.verify("R123", "FAIL", sign)).isFalse();
        assertThat(service.verify("R123", "SUCCESS", null)).isFalse();
        assertThat(service.verify("R123", "SUCCESS", "")).isFalse();
        assertThat(service.verify("R123", "SUCCESS", "deadbeef")).isFalse();
    }
}
