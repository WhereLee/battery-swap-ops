package com.swapops.server.admin.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端口令与 token 工具单测（S7 WP-A）：BCrypt 往返与随机 token 形态。
 */
@DisplayName("管理端密钥工具")
class AdminSecretsTest {

    @Test
    @DisplayName("BCrypt：哈希可校验且明文不出现于哈希")
    void bcrypt往返() {
        String hash = AdminSecrets.hashPassword("Ops#12345");
        assertThat(hash).startsWith("$2");
        assertThat(hash).doesNotContain("Ops#12345");
        assertThat(AdminSecrets.matches("Ops#12345", hash)).isTrue();
        assertThat(AdminSecrets.matches("wrong", hash)).isFalse();
        assertThat(AdminSecrets.matches(null, hash)).isFalse();
    }

    @Test
    @DisplayName("token：32 位十六进制且两次不同")
    void token形态() {
        String a = AdminSecrets.generateToken();
        String b = AdminSecrets.generateToken();
        assertThat(a).matches("^[0-9a-f]{32}$");
        assertThat(a).isNotEqualTo(b);
    }
}
