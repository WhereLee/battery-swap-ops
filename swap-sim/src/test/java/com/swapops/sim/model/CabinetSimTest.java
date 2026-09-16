package com.swapops.sim.model;

import com.swapops.contract.EventType;
import com.swapops.sim.config.SimProperties;
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
 * 模拟柜单测：seq 幂等双线、门锁故障双线、空仓开门（退租场景）、会话 seq 回带、
 * 门开后事件上报（异步）、取/还电事件、心跳状态。
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
        verify(reporter, timeout(5000)).report(argThat(m ->
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
    @DisplayName("门锁故障拒绝；曾被拒 seq 重试明确拒绝（双线）")
    void 门锁故障与双线拒绝() {
        cabinet.setDoorStuck(true);
        assertThatThrownBy(() -> cabinet.openCell(1, 1L, "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("门锁故障");
        assertThatThrownBy(() -> cabinet.openCell(1, 1L, "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("曾被拒绝");
    }

    @Test
    @DisplayName("空仓可开门（RETURN 退租场景：开空仓放入电池）")
    void 空仓可开门() {
        assertThat(cabinet.openCell(3, 2L, "t")).isTrue();
        verify(reporter, timeout(5000)).report(argThat(m ->
                m.eventType() == EventType.DOOR_OPENED && m.cellNo() == 3 && m.commandSeq() == 2L));
    }

    @Test
    @DisplayName("会话回带：开门会话内的取电事件回带同一 commandSeq")
    void 会话seq回带() {
        assertThat(cabinet.openCell(1, 9L, "t")).isTrue();
        cabinet.devTake(1, "t");
        verify(reporter, timeout(5000)).report(argThat(m ->
                m.eventType() == EventType.BATTERY_OUT && m.commandSeq() == 9L));
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
    @DisplayName("取电/还电：先 BATTERY_OUT 后 BATTERY_IN（无会话时 commandSeq 为空）")
    void 取还电事件() {
        cabinet.devTake(1, "t1");
        verify(reporter, timeout(5000)).report(argThat(m ->
                m.eventType() == EventType.BATTERY_OUT && m.commandSeq() == null));
        cabinet.devPut(1, "BAT-0001", 15, "t2");
        verify(reporter, timeout(5000)).report(argThat(m ->
                m.eventType() == EventType.BATTERY_IN && m.soc() == 15));
        assertThat(cabinet.snapshot().get("lastCommandSeq")).isEqualTo(0L);
    }

    @Test
    @DisplayName("联调电量上报：SOC_REPORT 携 soc、无 commandSeq（满电回池）")
    void 电量上报事件() {
        cabinet.devSoc(1, "BAT-0001", 100, "t-soc");
        verify(reporter, timeout(5000)).report(argThat(m ->
                m.eventType() == EventType.SOC_REPORT
                        && "BAT-0001".equals(m.batteryNo())
                        && Integer.valueOf(100).equals(m.soc())
                        && m.commandSeq() == null));
    }
}
