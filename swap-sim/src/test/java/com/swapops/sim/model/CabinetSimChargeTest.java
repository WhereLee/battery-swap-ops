package com.swapops.sim.model;

import com.swapops.sim.config.SimProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 充电仿真单测（S4.3）：策略版本单调、功率约束下 SOC 推进、预算 0 排队不充、功率不超限。
 */
@DisplayName("sim 充电仿真（策略+功率约束）")
class CabinetSimChargeTest {

    private SimProperties properties() {
        SimProperties properties = new SimProperties();
        properties.setCellsPerCabinet(4);
        properties.setFullCells(0);
        properties.setBatteryCapacityWh(1000);
        properties.setChargeEfficiency(1.0);
        properties.setChargeSpeedFactor(1);
        properties.setMaxChargePowerW(400);
        properties.setDefaultChargePowerW(400);
        properties.setChargeTickMillis(1000);
        return properties;
    }

    private CabinetSim cabinet(SimProperties properties) {
        return new CabinetSim("SWAP-C-TEST", "boot-x", properties, message -> { }, 0);
    }

    private int hour() {
        return java.time.LocalTime.now(java.time.ZoneId.systemDefault()).getHour();
    }

    @Test
    @DisplayName("版本单调：新版本应用、同版本幂等、旧版本拒绝")
    void 版本单调() {
        CabinetSim cabinet = cabinet(properties());
        assertThat(cabinet.applyPolicy(new ChargePolicy(1, 2, List.of(
                new ChargePolicy.Window(0, 24, 400))))).isTrue();
        assertThat(cabinet.applyPolicy(new ChargePolicy(1, 2, List.of(
                new ChargePolicy.Window(0, 24, 800))))).isFalse();
        assertThatThrownBy(() -> cabinet.applyPolicy(new ChargePolicy(0, 2, List.of(
                new ChargePolicy.Window(0, 24, 800))))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("功率约束：400W 预算 → 单颗 400W 充 1 小时 +40%（容量 1000Wh）")
    void 功率约束充电() {
        SimProperties properties = properties();
        CabinetSim cabinet = cabinet(properties);
        cabinet.devPut(1, "BAT-1", 20, "t");
        cabinet.devPut(2, "BAT-2", 20, "t");
        cabinet.applyPolicy(new ChargePolicy(1, 2, List.of(new ChargePolicy.Window(0, 24, 400))));

        cabinet.chargeTick(System.currentTimeMillis(), 3600_000L);

        var cells = cabinet.snapshot().get("cells");
        @SuppressWarnings("unchecked")
        var cell1 = (java.util.Map<String, Object>) ((java.util.Map<Integer, Object>) cells).get(1);
        @SuppressWarnings("unchecked")
        var cell2 = (java.util.Map<String, Object>) ((java.util.Map<Integer, Object>) cells).get(2);
        // 预算 400W 只够按上限充 1 颗：cell1 +40%，cell2 排队不变
        assertThat((Integer) cell1.get("soc")).isEqualTo(60);
        assertThat((Integer) cell2.get("soc")).isEqualTo(20);
        assertThat(cabinet.getChargingPowerW()).isEqualTo(400);
    }

    @Test
    @DisplayName("预算 0（峰时禁充）：不推进且功率为 0")
    void 零预算排队() {
        SimProperties properties = properties();
        CabinetSim cabinet = cabinet(properties);
        cabinet.devPut(1, "BAT-1", 20, "t");
        cabinet.applyPolicy(new ChargePolicy(1, 2, List.of(new ChargePolicy.Window(0, 24, 0))));

        cabinet.chargeTick(System.currentTimeMillis(), 3600_000L);

        assertThat(cabinet.getChargingPowerW()).isZero();
        var cells = (java.util.Map<?, ?>) cabinet.snapshot().get("cells");
        @SuppressWarnings("unchecked")
        var cell1 = (java.util.Map<String, Object>) cells.get(1);
        assertThat((Integer) cell1.get("soc")).isEqualTo(20);
    }

    @Test
    @DisplayName("默认策略（未下发）：用 sim 默认功率充电")
    void 默认策略() {
        CabinetSim cabinet = cabinet(properties());
        cabinet.devPut(1, "BAT-1", 10, "t");

        cabinet.chargeTick(System.currentTimeMillis(), 3600_000L);

        assertThat(cabinet.getChargingPowerW()).isEqualTo(400);
    }
}
