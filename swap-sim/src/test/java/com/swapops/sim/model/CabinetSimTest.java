package com.swapops.sim.model;

import com.swapops.contract.EventType;
import com.swapops.sim.config.SimProperties;
import com.swapops.sim.reporter.DeviceEventMessage;
import com.swapops.sim.reporter.EventReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 模拟柜单测：seq 幂等双线、空仓拒绝、门开后事件上报（异步）、取/还电事件。
 */
@DisplayName("模拟柜（协议 v1 行为）")
class CabinetSimTest {

    private EventReporter reporter;
    private CabinetSim cabinet;

    @BeforeEach
    void setUp() {
        SimProperties properties = new SimProperties();
        properties.setCellsPerCabinet(3);
        properties.setFullCells(2);
        properties.setOpenDelayMillis(0);
        reporter = mock(EventReporter.class);
        cabinet = new CabinetSim("SWAP-C-001", "boot-1", properties, reporter, 0);
    }

    @Test
    @DisplayName("开仓受理 → 异步上报 DOOR_OPENED（携 commandSeq/traceId）")
    void 开仓受理并上报() {
        boolean accepted = cabinet.openCell(1, 7L, "trace-abc");

        assertThat(accepted).isTrue();
        verify(reporter, timeout(2000)).report(argThat(m ->
                m.eventType() == EventType.DOOR_OPENED
                        && m.cellNo() == 1
                        && m.commandSeq() == 7L
                        && "trace-abc".equals(m.traceId())));
    }

    @Test
    @DisplayName("重复 seq 幂等忽略（不重复开门、不再上报）")
    void 重复seq幂等忽略() {
        assertThat(cabinet.openCell(1, 7L, "t1")).isTrue();
        assertThat(cabinet.openCell(1, 7L, "t2")).isFalse();
    }

    @Test
    @DisplayName("空仓拒绝；曾被拒 seq 重试明确拒绝（双线）")
    void 空仓与双线拒绝() {
        assertThatThrownBy(() -> cabinet.openCell(3, 1L, "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空仓");
        assertThatThrownBy(() -> cabinet.openCell(3, 1L, "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("曾被拒绝");
    }

    @Test
    @DisplayName("门锁故障注入：开仓被拒")
    void 门锁故障拒绝() {
        cabinet.setDoorStuck(true);
        assertThatThrownBy(() -> cabinet.openCell(1, 1L, "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("门锁故障");
    }

    @Test
    @DisplayName("心跳状态：无空仓上报 FULL(2)，出现空仓回落 ONLINE(1)")
    void 心跳状态按空仓计算() {
        SimProperties fullProps = new SimProperties();
        fullProps.setCellsPerCabinet(3);
        fullProps.setFullCells(3);
        fullProps.setOpenDelayMillis(0);
        CabinetSim full = new CabinetSim("SWAP-C-009", "boot-1", fullProps, mock(EventReporter.class), 0);

        assertThat(full.reportStatus()).isEqualTo(2);
        full.devTake(1, "t");
        assertThat(full.reportStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("取电/还电：先 BATTERY_OUT 后 BATTERY_IN，仓态可查询")
    void 取还电事件() {
        cabinet.devTake(1, "t1");
        verify(reporter, timeout(2000)).report(argThat(m -> m.eventType() == EventType.BATTERY_OUT));
        cabinet.devPut(1, "BAT-0001", 15, "t2");
        verify(reporter, timeout(2000)).report(argThat(m ->
                m.eventType() == EventType.BATTERY_IN && m.soc() == 15));
        assertThat(cabinet.snapshot().get("lastCommandSeq")).isEqualTo(0L);
    }
}
