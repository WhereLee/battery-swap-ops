package com.swapops.server.order.service.pay;

import com.swapops.server.config.PayProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 支付回调签名（S3.4）：HMAC-SHA256(secret, tradeNo + "|" + result) 小写 hex。
 * 校验用常量时间比较（防时序侧信道）。
 */
@Service
public class PaySignatureService {

    private final PayProperties payProperties;

    public PaySignatureService(PayProperties payProperties) {
        this.payProperties = payProperties;
    }

    public String sign(String tradeNo, String result) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(payProperties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((tradeNo + "|" + result).getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("支付签名计算失败", e);
        }
    }

    public boolean verify(String tradeNo, String result, String sign) {
        if (sign == null || sign.isBlank()) {
            return false;
        }
        String expected = sign(tradeNo, result);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sign.toLowerCase(Locale.ROOT).trim().getBytes(StandardCharsets.UTF_8));
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
