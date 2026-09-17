package com.swapops.agent.engine;

import com.swapops.agent.model.AlarmView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 规则引擎矩阵测试：14 种告警类型全覆盖 + 聚合/过滤/幂等键/文案。 */
class RuleEngineTest {

    private static final long NOW = 1_700_000_000_000L;

    private final RuleEngine engine = new RuleEngine();

    private AlarmView alarm(long id, String device, String type) {
        return alarm(id, device, type, NOW - 60_000, 0);
    }

    private AlarmView alarm(long id, String device, String type, long created, Integer handled) {
        return new AlarmView(id, "CABINET", device, type, "content-" + id, handled, created);
    }

    @Test
    void deviceFaultsSuggestWorkOrderWithSeverity() {
        List<Suggestion> out = engine.derive(List.of(
                alarm(1, "C-1", "CABINET_FAULT"),
                alarm(2, "C-2", "CELL_FAULT"),
                alarm(3, "C-3", "BATTERY_HEALTH_LOW"),
                alarm(4, "C-4", "RETRY_EXCEEDED"),
                alarm(5, "C-5", "WORK_ORDER_SLA_BREACH"),
                alarm(6, "C-6", "BATCH_OFFLINE")));
        assertThat(out).hasSize(6)
                .allMatch(s -> "CREATE_WORK_ORDER_FROM_ALARM".equals(s.actionType()));
        assertThat(out).extracting(Suggestion::severity)
                .containsExactly("HIGH", "HIGH", "MEDIUM", "MEDIUM", "HIGH", "HIGH");
    }

    @Test
    void reconDiffsSuggestReconcile() {
        List<Suggestion> out = engine.derive(List.of(
                alarm(7, "C-7", "RECONCILE_ERROR"),
                alarm(8, "C-8", "CHANNEL_RECON_DIFF")));
        assertThat(out).hasSize(2).allMatch(s -> "RUN_RECONCILE".equals(s.actionType()));
        assertThat(out).extracting(Suggestion::severity).containsExactly("MEDIUM", "HIGH");
    }

    @Test
    void restraintClassesSuggestNothing() {
        List<Suggestion> out = engine.derive(List.of(
                alarm(9, "C-9", "OFFLINE"),
                alarm(10, "C-10", "ORDER_OVERDUE"),
                alarm(11, "C-11", "ORDER_ARREARS"),
                alarm(12, "C-12", "JOB_STALLED"),
                alarm(13, "C-13", "OUTBOX_DEAD"),
                alarm(14, "C-14", "DELAY_DEAD")));
        assertThat(out).isEmpty();
    }

    @Test
    void unknownTypeFailsSafe() {
        assertThat(engine.derive(List.of(alarm(1, "C-1", "SOME_FUTURE_TYPE")))).isEmpty();
    }

    @Test
    void handledAlarmsIgnored() {
        assertThat(engine.derive(List.of(alarm(1, "C-1", "CABINET_FAULT", NOW, 1)))).isEmpty();
    }

    @Test
    void groupsSameDeviceSameTypeIntoOneSuggestionWithEarliestSource() {
        List<Suggestion> out = engine.derive(List.of(
                alarm(20, "C-9", "CABINET_FAULT", NOW - 1_000, 0),
                alarm(21, "C-9", "CABINET_FAULT", NOW - 5_000, 0)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).alarmId()).isEqualTo(21); // 最早一条为源
        assertThat(out.get(0).groupCount()).isEqualTo(2);
    }

    @Test
    void idemKeyAndReasonCarryContext() {
        Suggestion s = engine.derive(List.of(alarm(7, "C-7", "CHANNEL_RECON_DIFF"))).get(0);
        assertThat(s.idemKey()).isEqualTo("agent-7-run_reconcile");
        assertThat(s.reason()).contains("[agent-auto]", "CHANNEL_RECON_DIFF", "C-7", "HIGH", "id=7");
    }

    @Test
    void emptyInputYieldsNoSuggestions() {
        assertThat(engine.derive(List.of())).isEmpty();
        assertThat(engine.derive(null)).isEmpty();
    }
}
