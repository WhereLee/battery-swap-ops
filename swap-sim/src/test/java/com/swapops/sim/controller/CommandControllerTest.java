package com.swapops.sim.controller;

import com.swapops.contract.DeviceSignature;
import com.swapops.sim.config.SimProperties;
import com.swapops.sim.model.CabinetSim;
import com.swapops.sim.registry.SimRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * sim 指令入口单测（S4.3 补）：动作路由（默认开仓/策略）、策略验签与版本拒绝、查询失败分支。
 */
@DisplayName("sim 指令入口（路由/验签）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommandControllerTest {

    private static final String SECRET = "aabbccddeeff00112233445566778899";

    @Mock
    private SimRegistry registry;
    @Mock
    private CabinetSim cabinet;

    private SimProperties properties;
    private CommandController controller;

    @BeforeEach
    void setUp() {
        properties = new SimProperties();
        SimProperties.CabinetCfg cfg = new SimProperties.CabinetCfg();
        cfg.setCabinetNo("SWAP-C-001");
        cfg.setSecret(SECRET);
        properties.setCabinets(List.of(cfg));
        controller = new CommandController(registry, properties);
        when(registry.get("SWAP-C-001")).thenReturn(cabinet);
        when(cabinet.getCabinetNo()).thenReturn("SWAP-C-001");
    }

    private Map<String, Object> openBody(long seq) {
        Map<String, Object> body = new HashMap<>();
        body.put("cabinetNo", "SWAP-C-001");
        body.put("cellNo", 3);
        body.put("commandSeq", seq);
        return body;
    }

    private Map<String, Object> policyBody(long seq, long version) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "SET_CHARGE_POLICY");
        body.put("cabinetNo", "SWAP-C-001");
        body.put("commandSeq", seq);
        Map<String, Object> policy = new HashMap<>();
        policy.put("version", version);
        policy.put("priority", 2);
        policy.put("windows", List.of(Map.of("startHour", 0, "endHour", 24, "powerLimitW", 800)));
        body.put("policy", policy);
        return body;
    }

    @Test
    @DisplayName("默认动作=开仓：验签通过受理")
    void 默认开仓() {
        when(cabinet.openCell(org.mockito.ArgumentMatchers.eq(3), org.mockito.ArgumentMatchers.eq(7L), any())).thenReturn(true);
        String sign = DeviceSignature.sign(SECRET, DeviceSignature.canonicalCommand("SWAP-C-001", 3, 7L));

        Map<String, Object> resp = controller.cmd(sign, null, openBody(7L));

        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    @DisplayName("策略下发：canonicalPolicy 验签通过并返回 appliedVersion")
    void 策略下发() {
        when(cabinet.applyPolicy(any())).thenReturn(true);
        String sign = DeviceSignature.sign(SECRET, DeviceSignature.canonicalPolicy("SWAP-C-001", 1, 9L));

        Map<String, Object> resp = controller.cmd(sign, null, policyBody(9L, 1));

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat(String.valueOf(resp.get("data"))).contains("appliedVersion");
    }

    @Test
    @DisplayName("策略旧版本：柜侧拒绝 → code=1")
    void 策略旧版本拒绝() {
        when(cabinet.applyPolicy(any())).thenThrow(new IllegalStateException("策略版本回退被拒"));
        String sign = DeviceSignature.sign(SECRET, DeviceSignature.canonicalPolicy("SWAP-C-001", 0, 10L));

        Map<String, Object> resp = controller.cmd(sign, null, policyBody(10L, 0));

        assertThat(resp.get("code")).isEqualTo(1);
    }

    @Test
    @DisplayName("策略缺体/缺 seq：显式失败")
    void 策略参数缺失() {
        Map<String, Object> noPolicy = new HashMap<>();
        noPolicy.put("action", "SET_CHARGE_POLICY");
        noPolicy.put("cabinetNo", "SWAP-C-001");
        noPolicy.put("commandSeq", 11L);
        Map<String, Object> resp = controller.cmd("x", null, noPolicy);
        assertThat(resp.get("code")).isEqualTo(1);

        assertThatThrownBy(() -> controller.cmd("x", null, Map.of("cabinetNo", "SWAP-C-001")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("策略验签失败：401")
    void 策略验签失败() {
        assertThatThrownBy(() -> controller.cmd("bad-sign", null, policyBody(12L, 1)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("查询：缺 cabinetNo/未知柜 → code=1")
    void 查询失败分支() {
        assertThat(controller.query("x", null, Map.of()).get("code")).isEqualTo(1);

        String sign = DeviceSignature.sign(SECRET, DeviceSignature.canonicalQuery("SWAP-C-001"));
        when(registry.get("SWAP-C-001")).thenReturn(null);
        assertThat(controller.query(sign, null, Map.of("cabinetNo", "SWAP-C-001")).get("code")).isEqualTo(1);
    }
}
