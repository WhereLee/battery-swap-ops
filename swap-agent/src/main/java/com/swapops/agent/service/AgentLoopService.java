package com.swapops.agent.service;

import com.swapops.agent.client.PlatformClient;
import com.swapops.agent.config.AgentProperties;
import com.swapops.agent.engine.Suggestion;
import com.swapops.agent.engine.SuggestionEngine;
import com.swapops.agent.model.AlarmView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 主循环（心跳）：拉未处理告警 → 规则引擎推导 → 逐条 propose（写建议单，零副作用）。
 *
 * <p>可靠性取向：单条提交失败只记数不中断（下一轮幂等键天然重试）；enabled=false 时定时器空转；
 * 供数统计（scans/submitted/failed/lastScanAt）暴露在 /agent/status，剧本与运维可直接观测。
 */
@Slf4j
@Service
public class AgentLoopService {

    private final PlatformClient client;
    private final SuggestionEngine engine;
    private final AgentProperties props;

    private final AtomicLong scans = new AtomicLong();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile Long lastScanAt;
    private volatile int lastAlarmCount;
    private volatile int lastSuggestionCount;
    private volatile String lastError;

    public AgentLoopService(PlatformClient client, SuggestionEngine engine, AgentProperties props) {
        this.client = client;
        this.engine = engine;
        this.props = props;
    }

    /** 定时轮询（默认关；SWAP_AGENT_ENABLED=true 显式启用）。 */
    @Scheduled(initialDelayString = "1000", fixedDelayString = "${swap.agent.poll-interval-ms:30000}")
    public void scheduledScan() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            scanOnce();
        } catch (Exception e) {
            lastError = e.getMessage();
            log.error("[agent] 定时扫描失败: {}", e.getMessage());
        }
    }

    /** 执行一轮：拉告警 → 推导建议 → 逐条提交（幂等键去重，平台侧兜底）。返回本轮统计。 */
    public Map<String, Object> scanOnce() {
        if (props.getAdminToken() == null || props.getAdminToken().isBlank()) {
            throw new IllegalStateException("SWAP_AGENT_ADMIN_TOKEN 未配置，拒绝扫描");
        }
        long startedAt = System.currentTimeMillis();
        List<AlarmView> alarms = client.listOpenAlarms();
        List<Suggestion> suggestions = engine.derive(alarms);
        int ok = 0;
        int errors = 0;
        for (Suggestion suggestion : suggestions) {
            try {
                client.propose(suggestion.idemKey(), buildForm(suggestion));
                ok++;
            } catch (Exception e) {
                errors++;
                log.warn("[agent] 建议提交失败 key={} err={}", suggestion.idemKey(), e.getMessage());
            }
        }
        lastScanAt = startedAt;
        lastAlarmCount = alarms.size();
        lastSuggestionCount = suggestions.size();
        lastError = errors > 0 ? "partial: " + errors + " submit failures" : null;
        submitted.addAndGet(ok);
        failed.addAndGet(errors);
        long scanNo = scans.incrementAndGet();
        log.info("[agent] scan#{} alarms={} suggestions={} submitted={} errors={}",
                scanNo, alarms.size(), suggestions.size(), ok, errors);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("scanNo", scanNo);
        stats.put("alarms", alarms.size());
        stats.put("suggestions", suggestions.size());
        stats.put("submitted", ok);
        stats.put("errors", errors);
        stats.put("actions", suggestions.stream().map(s -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("idemKey", s.idemKey());
            item.put("actionType", s.actionType());
            item.put("alarmId", s.alarmId());
            item.put("severity", s.severity());
            return item;
        }).toList());
        return stats;
    }

    /** 运行态（供 /agent/status；platformUp 为实时探测）。 */
    public Map<String, Object> status() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", props.isEnabled());
        view.put("platformBaseUrl", props.getPlatformBaseUrl());
        view.put("platformUp", client.healthUp());
        view.put("scans", scans.get());
        view.put("submittedTotal", submitted.get());
        view.put("failedTotal", failed.get());
        view.put("lastScanAt", lastScanAt);
        view.put("lastAlarmCount", lastAlarmCount);
        view.put("lastSuggestionCount", lastSuggestionCount);
        view.put("lastError", lastError);
        return view;
    }

    private Map<String, Object> buildForm(Suggestion suggestion) {
        // 源 alarmId/severity 随 params 一起进建议单——审计可追溯到具体告警
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("alarmId", suggestion.alarmId());
        params.put("severity", suggestion.severity());
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("actionType", suggestion.actionType());
        form.put("reason", suggestion.reason());
        form.put("params", params);
        return form;
    }
}
