package com.swapops.agent.engine;

import com.swapops.agent.model.AlarmView;

import java.util.List;

/**
 * 建议引擎接口：告警集 → 建议集（纯函数，无 I/O）。
 *
 * <p>最小版实现为确定性规则引擎（{@link RuleEngine}）——可解释、可评测、零幻觉；
 * 将来若挂接 LLM 推理器，实现本接口替换即可，骨架（扫描/幂等/审计链路）不动。
 */
public interface SuggestionEngine {

    /** 从一批评告警推导建议（顺序稳定）。 */
    List<Suggestion> derive(List<AlarmView> alarms);
}
