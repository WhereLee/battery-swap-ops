package com.swapops.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 契约钉合测试（固定向量）：canonical 拼法与 HMAC 输出一旦改变即红——
 * 双端（平台/模拟器）共用本模块，任何语义漂移在 CI 立即暴露。
 */
@DisplayName("契约向量（协议 v1）")
class ContractVectorTest {

    private static final String SECRET = "aabbccddeeff00112233445566778899";

    @Test
    @DisplayName("canonical：null 归一为空串，字段序固定")
    void canonical_null归一并保序() {
        assertThat(DeviceSignature.canonicalEvent(
                "SWAP-C-001", EventType.BATTERY_OUT, 3, "BAT-000123", "boot-1", 43L))
                .isEqualTo("SWAP-C-001|BATTERY_OUT|3|BAT-000123|boot-1|43");
        assertThat(DeviceSignature.canonicalEvent(
                "SWAP-C-001", EventType.DOOR_CLOSED, 3, null, "boot-1", 44L))
                .isEqualTo("SWAP-C-001|DOOR_CLOSED|3||boot-1|44");
        assertThat(DeviceSignature.canonicalHeartbeat("SWAP-C-001", 1))
                .isEqualTo("SWAP-C-001|1");
        assertThat(DeviceSignature.canonicalCommand("SWAP-C-001", 3, 7L))
                .isEqualTo("SWAP-C-001|3|7");
        assertThat(DeviceSignature.canonicalPolicy("SWAP-C-001", 3, 9L))
                .isEqualTo("SWAP-C-001|POLICY|3|9");
        assertThat(DeviceSignature.canonicalQuery("SWAP-C-001"))
                .isEqualTo("SWAP-C-001|QUERY|0");
    }

    @Test
    @DisplayName("HMAC 固定向量（改动即破坏双端契约）")
    void hmac固定向量() {
        assertThat(DeviceSignature.sign(SECRET, "SWAP-C-001|BATTERY_OUT|3|BAT-000123|boot-1|43"))
                .isEqualTo("9f7a4507aaf2485c448a147edc344d380815c5144d0f5d8c8faf5b0fbc517699");
        assertThat(DeviceSignature.sign(SECRET, "SWAP-C-001|1"))
                .isEqualTo("d0111f2a6a98ba22bed538c537353981c7340c3f5e08b62d620d9605c7a31263");
        assertThat(DeviceSignature.sign(SECRET, "SWAP-C-001|3|7"))
                .isEqualTo("cb33dd8b834c32363b7935b4cb8ae8385b15641e8594acec6e9dc2aefed36152");
        assertThat(DeviceSignature.sign(SECRET, "SWAP-C-001|QUERY|0"))
                .isEqualTo("2478e9a62da7e6555f7e603d9076e4098d4936a7f3f31f63bda55697b31262ab");
    }

    @Test
    @DisplayName("verify：正例通过、空/错签拒绝（常量时间比对）")
    void verify分支() {
        String canonical = DeviceSignature.canonicalCommand("SWAP-C-001", 3, 7L);
        String sig = DeviceSignature.sign(SECRET, canonical);
        assertThat(DeviceSignature.verify(SECRET, canonical, sig)).isTrue();
        assertThat(DeviceSignature.verify(SECRET, canonical, null)).isFalse();
        assertThat(DeviceSignature.verify(SECRET, canonical, "")).isFalse();
        assertThat(DeviceSignature.verify(SECRET, canonical, sig.substring(0, 63) + "0")).isFalse();
    }

    @Test
    @DisplayName("枚举码值与 wire 目录稳定")
    void 枚举码值稳定() {
        assertThat(CabinetStatus.ONLINE.getCode()).isEqualTo(1);
        assertThat(CellStatus.OCCUPIED.getCode()).isEqualTo(2);
        assertThat(BatteryStatus.FULL.getCode()).isEqualTo(2);
        assertThat(CommandStatus.PENDING.getCode()).isEqualTo(1);
        assertThat(OrderStatus.PENDING_OPEN.getCode()).isEqualTo(1);
        assertThat(EventType.fromWire("BATTERY_IN")).isEqualTo(EventType.BATTERY_IN);
        assertThatThrownBy(() -> EventType.fromWire("NOPE"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
