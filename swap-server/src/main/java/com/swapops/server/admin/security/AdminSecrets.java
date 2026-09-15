package com.swapops.server.admin.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.SecureRandom;

/**
 * 管理端口令与 token 工具（S7 WP-A）：BCrypt 慢哈希（壳子 PasswordCodec 同款）+ SecureRandom token
 * （壳子 TokenGenerator 同款，禁用可预测来源）。
 */
public final class AdminSecrets {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private AdminSecrets() {
    }

    public static String hashPassword(String plain) {
        return ENCODER.encode(plain);
    }

    public static boolean matches(String plain, String hash) {
        return plain != null && hash != null && ENCODER.matches(plain, hash);
    }

    /** 生成 128 位安全随机 token（32 位十六进制） */
    public static String generateToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }
}
