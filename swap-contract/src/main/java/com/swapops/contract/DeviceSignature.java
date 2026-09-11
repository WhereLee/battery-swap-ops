package com.swapops.contract;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 设备通道 HMAC 签名（协议 v1，S0.3 §4）——平台与模拟器共用同一实现。
 *
 * <p>canonical（null → 空串，字段序固定）：
 * 事件 cabinetNo|eventType|cellNo|batteryNo|bootId|eventSeq；
 * 心跳 cabinetNo|status；指令 cabinetNo|cellNo|commandSeq。
 * 比对用常量时间 MessageDigest.isEqual。</p>
 */
public final class DeviceSignature {

    private static final String HMAC_ALGO = "HmacSHA256";

    private DeviceSignature() {
    }

    public static String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    public static boolean verify(String secret, String canonical, String signature) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        String expected = sign(secret, canonical);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    public static String canonicalEvent(String cabinetNo, EventType eventType, Integer cellNo,
                                        String batteryNo, String bootId, Long eventSeq) {
        return join(nz(cabinetNo), nz(eventType == null ? null : eventType.name()),
                cellNo == null ? "" : String.valueOf(cellNo),
                nz(batteryNo), nz(bootId), eventSeq == null ? "" : String.valueOf(eventSeq));
    }

    public static String canonicalHeartbeat(String cabinetNo, Integer status) {
        return join(nz(cabinetNo), status == null ? "" : String.valueOf(status));
    }

    public static String canonicalCommand(String cabinetNo, Integer cellNo, Long commandSeq) {
        return join(nz(cabinetNo), cellNo == null ? "" : String.valueOf(cellNo),
                commandSeq == null ? "" : String.valueOf(commandSeq));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String join(String... parts) {
        return String.join("|", parts);
    }
}
