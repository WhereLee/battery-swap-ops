package com.swapops.agent.controller;

import com.swapops.agent.client.PlatformClient;
import com.swapops.agent.config.AgentProperties;
import com.swapops.agent.engine.DiagnosisService;
import com.swapops.agent.engine.IncidentSummarizer;
import com.swapops.agent.service.AgentLoopService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 自有运维端点（:8700）：状态 / 手动扫描 / 设备问答 / 事故摘要。
 * 只读 + propose（propose 零副作用）——Agent 无任何"直接执行"通道。
 */
@RestController
@RequestMapping("agent")
public class AgentController {

    private final AgentLoopService loopService;
    private final DiagnosisService diagnosisService;
    private final IncidentSummarizer summarizer;
    private final PlatformClient client;
    private final AgentProperties props;

    public AgentController(AgentLoopService loopService, DiagnosisService diagnosisService,
                           IncidentSummarizer summarizer, PlatformClient client, AgentProperties props) {
        this.loopService = loopService;
        this.diagnosisService = diagnosisService;
        this.summarizer = summarizer;
        this.client = client;
        this.props = props;
    }

    /** 运行态：启用开关、平台连通、累计计数、上轮快照。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return loopService.status();
    }

    /** 手动触发一轮扫描（显式动作，供演示/剧本；不受 enabled 开关限制）。 */
    @PostMapping("/scan")
    public Map<String, Object> scan() {
        return loopService.scanOnce();
    }

    /** 设备问答：现状 / 诊断 / 建议。 */
    @GetMapping("/diagnose")
    public Map<String, Object> diagnose(@RequestParam String deviceNo) {
        return diagnosisService.diagnose(deviceNo, client.listOpenAlarms(), System.currentTimeMillis());
    }

    /** 事故摘要：窗口内按设备聚合的"人话"列表。 */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) Integer windowMinutes) {
        int minutes = windowMinutes == null ? props.getSummaryWindowMinutes() : windowMinutes;
        long now = System.currentTimeMillis();
        List<IncidentSummarizer.DeviceIncident> incidents =
                summarizer.summarize(client.listOpenAlarms(), now, minutes * 60_000L);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("windowMinutes", minutes);
        view.put("deviceCount", incidents.size());
        view.put("devices", incidents.stream().map(i -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceNo", i.deviceNo());
            item.put("count", i.count());
            item.put("byType", i.byType());
            item.put("text", summarizer.render(i, now));
            return item;
        }).toList());
        return view;
    }
}
