package com.swapops.server.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付参数（swap.pay.*）。资金入口不接受空凭证：secret 未配置（或格式非法）启动即失败，
 * 与 AdminTokenFilter 同一安全姿态（经由环境变量 SWAP_PAY_SECRET 注入）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.pay")
public class PayProperties {

    /** 回调签名密钥（HMAC-SHA256；32~64 位 hex；仓库零明文） */
    private String secret;

    /** mock 支付页地址前缀（仅联调；tradeNo 拼在其后） */
    private String mockPageBaseUrl = "http://127.0.0.1:8400/api/pay/mock";

    /** 单笔充值上限（分） */
    private int maxRechargeFen = 50000;

    @PostConstruct
    void validate() {
        if (secret == null || !secret.matches("^[0-9a-fA-F]{32,64}$")) {
            throw new IllegalStateException(
                    "swap.pay.secret 未配置或格式非法（32~64 位 hex，经环境变量 SWAP_PAY_SECRET 注入）");
        }
    }
}
