package com.swapops.agent.engine;

import com.swapops.agent.model.AlarmView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性规则引擎（最小版建议引擎）：告警类型 → 建议动作 的显式映射表。
 *
 * <p>设计取向——**克制优先**：只对"确定性现场故障"建工单、对"账务差异"触发对账；
 * 资金类（欠费/超时费）绝不触碰，系统类（任务停摆/死信）留给人工排查，单柜离线只观察
 * （多为网络抖动，建单即噪音）。动作类型与严重度全部可从下表逐行解释——可评测、零幻觉。
 */
@Slf4j
@Component
public class RuleEngine implements SuggestionEngine {

    /** 规则条目：actionType=null 表示"明确不建议"（克制声明，与"未知类型"区分但行为一致）。 */
    private record Rule(String actionType, String severity) {
    }

    private static final Map<String, Rule> RULES = Map.ofEntries(
            // —— 设备故障类：建工单现场处置 ——
            Map.entry("CABINET_FAULT", new Rule("CREATE_WORK_ORDER_FROM_ALARM", "HIGH")),
            Map.entry("CELL_FAULT", new Rule("CREATE_WORK_ORDER_FROM_ALARM", "HIGH")),
            Map.entry("BATTERY_HEALTH_LOW", new Rule("CREATE_WORK_ORDER_FROM_ALARM", "MEDIUM")),
            Map.entry("RETRY_EXCEEDED", new Rule("CREATE_WORK_ORDER_FROM_ALARM", "MEDIUM")),
            Map.entry("WORK_ORDER_SLA_BREACH", new Rule("CREATE_WORK_ORDER_FROM_ALARM", "HIGH")),
            Map.entry("BATCH_OFFLINE", new Rule("CREATE_WORK_ORDER_FROM_ALARM", "HIGH")),
            // —— 账务差异类：触发对账（平台既有幂等任务） ——
            Map.entry("RECONCILE_ERROR", new Rule("RUN_RECONCILE", "MEDIUM")),
            Map.entry("CHANNEL_RECON_DIFF", new Rule("RUN_RECONCILE", "HIGH")),
            // —— 明确不建议（克制声明） ——
            Map.entry("OFFLINE", new Rule(null, "LOW")),
            Map.entry("ORDER_OVERDUE", new Rule(null, "MEDIUM")),
            Map.entry("ORDER_ARREARS", new Rule(null, "MEDIUM")),
            Map.entry("JOB_STALLED", new Rule(null, "HIGH")),
            Map.entry("OUTBOX_DEAD", new Rule(null, "HIGH")),
            Map.entry("DELAY_DEAD", new Rule(null, "HIGH")));

    @Override
    public List<Suggestion> derive(List<AlarmView> alarms) {
        if (alarms == null || alarms.isEmpty()) {
            return List.of();
        }
        // 过滤（handled 双保险）+ 按 设备+类型 聚合（同设备同类告警合并为一条建议，输出顺序稳定）
        Map<String, List<AlarmView>> groups = new LinkedHashMap<>();
        for (AlarmView alarm : alarms) {
            if (alarm == null || alarm.alarmType() == null
                    || alarm.handled() == null || alarm.handled() != 0) {
                continue;
            }
            String key = (alarm.deviceNo() == null ? "-" : alarm.deviceNo()) + "|" + alarm.alarmType();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(alarm);
        }
        List<Suggestion> suggestions = new ArrayList<>();
        for (List<AlarmView> group : groups.values()) {
            AlarmView first = group.stream()
                    .min(Comparator.comparingLong(a -> a.createTime() == null ? 0L : a.createTime()))
                    .orElseThrow();
            Rule rule = RULES.get(first.alarmType());
            if (rule == null) {
                log.info("[agent] 未知告警类型（fail-safe 不动手）: {}", first.alarmType());
                continue;
            }
            if (rule.actionType() == null) {
                continue; // 明确不建议
            }
            suggestions.add(new Suggestion(rule.actionType(), first.id(), first.deviceNo(),
                    first.alarmType(), rule.severity(), group.size(),
                    buildReason(first, rule, group.size())));
        }
        return suggestions;
    }

    private String buildReason(AlarmView first, Rule rule, int count) {
        String device = first.deviceNo() == null ? "-" : first.deviceNo();
        return "[agent-auto] " + first.alarmType() + " x" + count + " @ " + device
                + "；severity=" + rule.severity()
                + "；首现告警 id=" + first.id()
                + "；建议 " + rule.actionType() + "（人工确认后执行）";
    }
}
