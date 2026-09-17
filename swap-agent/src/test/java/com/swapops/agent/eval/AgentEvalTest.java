package com.swapops.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.agent.engine.DiagnosisService;
import com.swapops.agent.engine.IncidentSummarizer;
import com.swapops.agent.engine.RuleEngine;
import com.swapops.agent.engine.Suggestion;
import com.swapops.agent.model.AlarmView;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S6 评测集 runner（20 题）：suggest（规则引擎）/ diagnose（设备问答）/ summary（事故摘要）三类断言。
 * 通过率即验收口径（roadmap 验收线 ≥15/20；本实现回归基线锁定全绿）。
 * stdout 输出 "EVAL RESULT: n/20 passed" 供剧本与证据文件留档。
 */
class AgentEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void evaluationSuitePassesAll() throws Exception {
        JsonNode root;
        try (InputStream in = AgentEvalTest.class.getResourceAsStream("/eval/cases.json")) {
            assertThat(in).as("评测集文件存在").isNotNull();
            root = MAPPER.readTree(in);
        }
        long now = root.path("now").asLong();

        RuleEngine engine = new RuleEngine();
        DiagnosisService diagnosis = new DiagnosisService(engine);
        IncidentSummarizer summarizer = new IncidentSummarizer();

        int pass = 0;
        int fail = 0;
        List<String> report = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            String id = c.path("id").asText();
            String kind = c.path("kind").asText("suggest");
            List<AlarmView> alarms = MAPPER.convertValue(c.path("alarms"), new TypeReference<List<AlarmView>>() { });
            boolean ok;
            String detail;
            switch (kind) {
                case "diagnose" -> {
                    Map<String, Object> view = diagnosis.diagnose(c.path("deviceNo").asText(), alarms, now);
                    String answer = String.valueOf(view.get("answer"));
                    ok = containsAll(answer, c.path("expect").path("answerContains"));
                    detail = answer;
                }
                case "summary" -> {
                    int windowMinutes = c.path("windowMinutes").asInt(60);
                    List<IncidentSummarizer.DeviceIncident> incidents =
                            summarizer.summarize(alarms, now, windowMinutes * 60_000L);
                    JsonNode expect = c.path("expect");
                    ok = incidents.size() == expect.path("deviceCount").asInt(-1)
                            && !incidents.isEmpty()
                            && incidents.get(0).deviceNo().equals(expect.path("firstDevice").asText())
                            && containsAll(summarizer.render(incidents.get(0), now), expect.path("textContains"));
                    detail = incidents.isEmpty() ? "[]" : summarizer.render(incidents.get(0), now);
                }
                default -> {
                    List<Suggestion> out = engine.derive(alarms);
                    ok = matchActions(out, c.path("expect").path("actions"));
                    detail = out.toString();
                }
            }
            if (ok) {
                pass++;
            } else {
                fail++;
            }
            report.add((ok ? "PASS " : "FAIL ") + id + " " + c.path("desc").asText() + (ok ? "" : " -> " + detail));
        }
        System.out.println("EVAL RESULT: " + pass + "/" + (pass + fail) + " passed");
        report.forEach(System.out::println);
        // 证据文件：显式 UTF-8（控制台转发编码随环境变化，文件不受影响）——供剧本/归档拷贝
        String evidence = "EVAL RESULT: " + pass + "/" + (pass + fail) + " passed\n" + String.join("\n", report) + "\n";
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("target", "eval-result.txt"),
                    evidence, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            System.out.println("WARN: cannot write target/eval-result.txt: " + e.getMessage());
        }
        assertThat(fail).as("评测集失败明细见 stdout").isZero();
    }

    private boolean matchActions(List<Suggestion> out, JsonNode expected) {
        if (out.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < out.size(); i++) {
            JsonNode e = expected.get(i);
            if (!out.get(i).actionType().equals(e.path("actionType").asText())) {
                return false;
            }
            if (out.get(i).alarmId() != e.path("alarmId").asLong()) {
                return false;
            }
            if (!out.get(i).severity().equals(e.path("severity").asText())) {
                return false;
            }
        }
        return true;
    }

    private boolean containsAll(String text, JsonNode fragments) {
        for (JsonNode fragment : fragments) {
            if (!text.contains(fragment.asText())) {
                return false;
            }
        }
        return true;
    }
}
