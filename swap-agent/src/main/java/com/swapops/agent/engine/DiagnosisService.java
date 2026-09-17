package com.swapops.agent.engine;

import com.swapops.agent.model.AlarmView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备问答（最小版）：给定设备号 + 未处理告警全集，输出"现状 / 诊断 / 建议"三段式回答。
 * 复用 {@link RuleEngine} 对单设备子集推导建议——问答与建议单同源，不搞两套逻辑。
 */
@Component
public class DiagnosisService {

    private final SuggestionEngine engine;

    public DiagnosisService(SuggestionEngine engine) {
        this.engine = engine;
    }

    /** 诊断回答；answer 为面向人的自然语言文本。 */
    public Map<String, Object> diagnose(String deviceNo, List<AlarmView> allOpenAlarms, long now) {
        List<AlarmView> mine = new ArrayList<>();
        if (allOpenAlarms != null) {
            for (AlarmView alarm : allOpenAlarms) {
                if (alarm != null && deviceNo.equals(alarm.deviceNo())
                        && alarm.handled() != null && alarm.handled() == 0) {
                    mine.add(alarm);
                }
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deviceNo", deviceNo);
        view.put("openAlarmCount", mine.size());
        if (mine.isEmpty()) {
            view.put("suggestedActions", List.of());
            view.put("answer", deviceNo + " 当前无未处理告警，无需动作。");
            return view;
        }
        List<Suggestion> suggestions = engine.derive(mine);
        List<String> actions = suggestions.stream()
                .map(s -> s.actionType() + "（severity=" + s.severity() + "，源告警 id=" + s.alarmId() + "）")
                .toList();
        Map<String, Integer> byType = new LinkedHashMap<>();
        long first = Long.MAX_VALUE;
        for (AlarmView alarm : mine) {
            byType.merge(alarm.alarmType() == null ? "-" : alarm.alarmType(), 1, Integer::sum);
            first = Math.min(first, alarm.createTime() == null ? 0L : alarm.createTime());
        }
        long firstFinal = first;
        String summary = deviceNo + " 未处理 " + mine.size() + " 条（"
                + byType.entrySet().stream().map(e -> e.getKey() + " x" + e.getValue())
                        .reduce((a, b) -> a + "、" + b).orElse("-")
                + "），首现 " + IncidentSummarizer.ageText(firstFinal, now) + " 前";
        String answer = summary + "。诊断："
                + (suggestions.isEmpty()
                        ? "命中均为观察/人工类规则，Agent 不建单（克制策略）。"
                        : "命中 " + suggestions.size() + " 条运维建议。")
                + (actions.isEmpty() ? "建议：暂无需 Agent 动作，请人工关注。" : "建议：" + String.join("；", actions) + "。");
        view.put("suggestedActions", actions);
        view.put("answer", answer);
        return view;
    }
}
