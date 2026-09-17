package com.swapops.agent.engine;

import com.swapops.agent.model.AlarmView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 设备问答测试：无告警 / 有故障建议 / 克制类三态 + 过滤准确性。 */
class DiagnosisServiceTest {

    private static final long NOW = 1_700_000_000_000L;

    private final DiagnosisService service = new DiagnosisService(new RuleEngine());

    private AlarmView alarm(long id, String device, String type, Integer handled) {
        return new AlarmView(id, "CABINET", device, type, "c-" + id, handled, NOW - 30 * 60_000);
    }

    @Test
    void noAlarmsMeansNoAction() {
        Map<String, Object> view = service.diagnose("SWAP-C-005", List.of(), NOW);
        assertThat(view.get("openAlarmCount")).isEqualTo(0);
        assertThat((String) view.get("answer")).contains("无未处理告警");
        assertThat((List<?>) view.get("suggestedActions")).isEmpty();
    }

    @Test
    void faultDeviceGetsWorkOrderSuggestion() {
        Map<String, Object> view = service.diagnose("SWAP-C-005", List.of(
                alarm(1, "SWAP-C-005", "CABINET_FAULT", 0),
                alarm(2, "SWAP-C-005", "OFFLINE", 0)), NOW);
        assertThat(view.get("openAlarmCount")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<String> actions = (List<String>) view.get("suggestedActions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0)).contains("CREATE_WORK_ORDER_FROM_ALARM").contains("id=1");
        assertThat((String) view.get("answer")).contains("未处理 2 条").contains("建议");
    }

    @Test
    void restraintOnlyDeviceStaysUntouched() {
        Map<String, Object> view = service.diagnose("SWAP-C-006",
                List.of(alarm(3, "SWAP-C-006", "OFFLINE", 0)), NOW);
        assertThat((List<?>) view.get("suggestedActions")).isEmpty();
        assertThat((String) view.get("answer")).contains("克制").contains("暂无需");
    }

    @Test
    void filtersOtherDevicesAndHandledAlarms() {
        Map<String, Object> view = service.diagnose("SWAP-C-005", List.of(
                alarm(1, "SWAP-C-005", "CABINET_FAULT", 0),
                alarm(2, "SWAP-C-005", "CABINET_FAULT", 1),
                alarm(3, "SWAP-C-006", "CABINET_FAULT", 0),
                alarm(4, "SWAP-C-005", "OFFLINE", 0)), NOW);
        assertThat(view.get("openAlarmCount")).isEqualTo(2);
    }
}
