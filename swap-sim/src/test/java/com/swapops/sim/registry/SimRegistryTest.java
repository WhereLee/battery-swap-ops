package com.swapops.sim.registry;

import com.swapops.sim.config.SimProperties;
import com.swapops.sim.reporter.EventReporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模拟器注册表单测（S4.3 补）：密钥 fail-fast、柜装载与查找。
 */
@DisplayName("sim 注册表（fail-fast）")
class SimRegistryTest {

    private SimProperties properties(String secret) {
        SimProperties properties = new SimProperties();
        properties.setCellsPerCabinet(2);
        properties.setFullCells(1);
        SimProperties.CabinetCfg cfg = new SimProperties.CabinetCfg();
        cfg.setCabinetNo("SWAP-C-001");
        cfg.setSecret(secret);
        properties.setCabinets(List.of(cfg));
        return properties;
    }

    @Test
    @DisplayName("合法密钥：装载柜并可查找（含电池编号偏移）")
    void 装载() {
        SimRegistry registry = new SimRegistry(properties("aabbccddeeff00112233445566778899"), message -> { });
        registry.init();

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get("SWAP-C-001")).isNotNull();
        assertThat(registry.get("SWAP-C-999")).isNull();
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    @DisplayName("密钥缺失/非法：启动即失败（50 台静默事故形态防护）")
    void 密钥failFast() {
        SimRegistry missing = new SimRegistry(properties(null), message -> { });
        assertThatThrownBy(missing::init).isInstanceOf(IllegalStateException.class);

        SimRegistry bad = new SimRegistry(properties("short"), message -> { });
        assertThatThrownBy(bad::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("dev 重置：bootId 轮换 + 柜重建到种子态")
    void 重置换代际() {
        SimRegistry registry = new SimRegistry(properties("aabbccddeeff00112233445566778899"), message -> { });
        registry.init();
        String bootIdBefore = registry.getBootId();
        com.swapops.sim.model.CabinetSim before = registry.get("SWAP-C-001");
        before.devTake(1, "trace-1");

        registry.reset();

        assertThat(registry.getBootId()).isNotEqualTo(bootIdBefore);
        com.swapops.sim.model.CabinetSim after = registry.get("SWAP-C-001");
        assertThat(after).isNotNull().isNotSameAs(before);
        assertThat(after.getBootId()).isEqualTo(registry.getBootId());
        assertThat(after.snapshot().get("cells").toString()).contains("BAT-0001");
    }
}
