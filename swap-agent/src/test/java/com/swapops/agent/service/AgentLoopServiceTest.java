package com.swapops.agent.service;

import com.swapops.agent.client.PlatformClient;
import com.swapops.agent.config.AgentProperties;
import com.swapops.agent.engine.RuleEngine;
import com.swapops.agent.model.AlarmView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 主循环测试：提交次数与幂等键、单条失败不中断、token 缺失拒绝、enabled 开关语义、status 计数。 */
class AgentLoopServiceTest {

    private static final long NOW = 1_700_000_000_000L;

    private PlatformClient client;
    private AgentProperties props;
    private AgentLoopService loop;

    @BeforeEach
    void setUp() {
        client = mock(PlatformClient.class);
        props = new AgentProperties();
        props.setAdminToken("t");
        loop = new AgentLoopService(client, new RuleEngine(), props);
    }

    private AlarmView alarm(long id, String device, String type) {
        return new AlarmView(id, "CABINET", device, type, "c-" + id, 0, NOW - 60_000);
    }

    @Test
    void scanSubmitsOneProposalPerSuggestionWithIdemKey() {
        when(client.listOpenAlarms()).thenReturn(List.of(
                alarm(1, "C-1", "CABINET_FAULT"),
                alarm(2, "C-2", "RECONCILE_ERROR"),
                alarm(3, "C-3", "ORDER_ARREARS")));
        when(client.propose(anyString(), anyMap())).thenReturn(null);

        Map<String, Object> stats = loop.scanOnce();

        assertThat(stats.get("alarms")).isEqualTo(3);
        assertThat(stats.get("suggestions")).isEqualTo(2);
        assertThat(stats.get("submitted")).isEqualTo(2);
        assertThat(stats.get("errors")).isEqualTo(0);
        verify(client).propose(eq("agent-1-create_work_order_from_alarm"), anyMap());
        verify(client).propose(eq("agent-2-run_reconcile"), anyMap());
    }

    @Test
    void singleFailureCountsAndDoesNotAbort() {
        when(client.listOpenAlarms()).thenReturn(List.of(
                alarm(1, "C-1", "CABINET_FAULT"),
                alarm(2, "C-2", "CELL_FAULT")));
        when(client.propose(eq("agent-1-create_work_order_from_alarm"), anyMap()))
                .thenThrow(new IllegalStateException("platform boom"));
        when(client.propose(eq("agent-2-create_work_order_from_alarm"), anyMap())).thenReturn(null);

        Map<String, Object> stats = loop.scanOnce();

        assertThat(stats.get("submitted")).isEqualTo(1);
        assertThat(stats.get("errors")).isEqualTo(1);
        verify(client).propose(eq("agent-2-create_work_order_from_alarm"), anyMap());
    }

    @Test
    void missingTokenRejectsScan() {
        props.setAdminToken("");
        assertThatThrownBy(() -> loop.scanOnce())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SWAP_AGENT_ADMIN_TOKEN");
    }

    @Test
    void scheduledScanIsNoopWhenDisabled() {
        props.setEnabled(false);
        loop.scheduledScan();
        verifyNoInteractions(client);
    }

    @Test
    void scheduledScanRunsWhenEnabledAndSurvivesErrors() {
        props.setEnabled(true);
        when(client.listOpenAlarms()).thenThrow(new IllegalStateException("down"));
        loop.scheduledScan(); // 不抛出（记 lastError）
        verify(client).listOpenAlarms();
        assertThat(loop.status().get("lastError")).isEqualTo("down");
    }

    @Test
    void statusReflectsCountersAndHealth() {
        when(client.healthUp()).thenReturn(true);
        when(client.listOpenAlarms()).thenReturn(List.of(alarm(1, "C-1", "CABINET_FAULT")));
        when(client.propose(anyString(), anyMap())).thenReturn(null);

        loop.scanOnce();
        Map<String, Object> status = loop.status();

        assertThat(status.get("platformUp")).isEqualTo(true);
        assertThat(status.get("scans")).isEqualTo(1L);
        assertThat(status.get("submittedTotal")).isEqualTo(1L);
        assertThat(status.get("lastAlarmCount")).isEqualTo(1);
    }
}
