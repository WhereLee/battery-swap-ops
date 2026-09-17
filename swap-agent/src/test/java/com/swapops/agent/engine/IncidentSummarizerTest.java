package com.swapops.agent.engine;

import com.swapops.agent.model.AlarmView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 事故摘要器测试：窗口过滤、聚合排序、人话渲染、相对时间边界。 */
class IncidentSummarizerTest {

    private static final long NOW = 1_700_000_000_000L;

    private final IncidentSummarizer summarizer = new IncidentSummarizer();

    private AlarmView alarm(long id, String device, String type, long created) {
        return new AlarmView(id, "CABINET", device, type, "c-" + id, 0, created);
    }

    @Test
    void windowExcludesOldAlarms() {
        List<IncidentSummarizer.DeviceIncident> out = summarizer.summarize(List.of(
                alarm(1, "A", "CABINET_FAULT", NOW - 30 * 60_000),
                alarm(2, "A", "OFFLINE", NOW - 3 * 3_600_000L)), NOW, 3_600_000);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).count()).isEqualTo(1);
        assertThat(out.get(0).byType()).containsOnlyKeys("CABINET_FAULT");
    }

    @Test
    void aggregatesByDeviceAndSortsByCountDesc() {
        List<IncidentSummarizer.DeviceIncident> out = summarizer.summarize(List.of(
                alarm(1, "A", "CABINET_FAULT", NOW - 60_000),
                alarm(2, "A", "OFFLINE", NOW - 30_000),
                alarm(3, "B", "CELL_FAULT", NOW - 50_000),
                alarm(4, "B", "CABINET_FAULT", NOW - 40_000),
                alarm(5, "B", "OFFLINE", NOW - 20_000)), NOW, 3_600_000);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).deviceNo()).isEqualTo("B");
        assertThat(out.get(0).count()).isEqualTo(3);
        assertThat(out.get(1).deviceNo()).isEqualTo("A");
    }

    @Test
    void renderProducesHumanText() {
        List<IncidentSummarizer.DeviceIncident> out = summarizer.summarize(List.of(
                alarm(1, "SWAP-C-005", "CABINET_FAULT", NOW - 34 * 60_000),
                alarm(2, "SWAP-C-005", "OFFLINE", NOW - 12 * 60_000)), NOW, 3_600_000);
        String text = summarizer.render(out.get(0), NOW);
        assertThat(text).contains("SWAP-C-005 未处理 2 条（CABINET_FAULT x1、OFFLINE x1）")
                .contains("首现 34m")
                .contains("最近 12m");
    }

    @Test
    void ageTextBoundaries() {
        assertThat(IncidentSummarizer.ageText(NOW, NOW)).isEqualTo("just now");
        assertThat(IncidentSummarizer.ageText(NOW - 34 * 60_000, NOW)).isEqualTo("34m");
        assertThat(IncidentSummarizer.ageText(NOW - (2 * 3600 + 10 * 60) * 1000L, NOW)).isEqualTo("2h10m");
        assertThat(IncidentSummarizer.ageText(NOW - 50 * 3600_000L, NOW)).isEqualTo("2d2h");
    }
}
