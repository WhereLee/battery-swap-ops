package com.swapops.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.agent.config.AgentProperties;
import com.swapops.agent.model.AlarmView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 平台 HTTP 客户端（Agent 唯一出站口）。
 *
 * <p>报文体形态显式保守：HTTP/1.1 + 定长 body（Content-Length）——对称于平台下行修复
 * （chunked 会被最简对端读空 body 的协议教训），Agent 对外只发最保守形态。
 *
 * <p>信封约定：平台 Result = {code, msg, data}，code==0 成功。
 */
@Slf4j
@Component
public class PlatformClient {

    private final AgentProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlatformClient(AgentProperties props) {
        if (props.isEnabled() && (props.getAdminToken() == null || props.getAdminToken().isBlank())) {
            throw new IllegalStateException(
                    "swap.agent.enabled=true 但 SWAP_AGENT_ADMIN_TOKEN 未注入（密钥零明文：经环境变量传入）");
        }
        this.props = props;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .build();
    }

    /** 未处理告警列表（只读）。 */
    public List<AlarmView> listOpenAlarms() {
        JsonNode data = get("/admin/alarm?handled=0&limit=" + props.getAlarmLimit());
        List<AlarmView> alarms = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode node : data) {
                alarms.add(mapper.convertValue(node, AlarmView.class));
            }
        }
        return alarms;
    }

    /** 平台存活探测（/actuator/health 免 token）。 */
    public boolean healthUp() {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri("/actuator/health"))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .GET().build();
            return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 提交建议（唯一写动作，本身零副作用；平台侧幂等键去重）。返回建议单 field 视图。 */
    public JsonNode propose(String idemKey, Map<String, Object> form) {
        JsonNode data = post("/admin/agent-action", form, idemKey);
        return data;
    }

    /** 建议单列表（审计视图，只读）。 */
    public JsonNode listActions(int status, int limit) {
        return get("/admin/agent-action?status=" + status + "&limit=" + limit);
    }

    private JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("X-Admin-Token", props.getAdminToken())
                .GET().build();
        return send(request);
    }

    private JsonNode post(String path, Map<String, Object> body, String idemKey) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("请求体序列化失败: " + e.getMessage(), e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("X-Admin-Token", props.getAdminToken());
        if (idemKey != null && !idemKey.isBlank()) {
            builder.header("Idempotency-Key", idemKey);
        }
        // ofString 自带 Content-Length（定长）——不落 chunked
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("平台请求被中断: " + request.uri(), e);
        } catch (Exception e) {
            throw new IllegalStateException("平台请求失败: " + request.uri() + " -> " + e.getMessage(), e);
        }
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("平台返回 " + response.statusCode() + ": " + truncate(response.body()));
        }
        try {
            JsonNode envelope = mapper.readTree(response.body());
            int code = envelope.path("code").asInt(-1);
            if (code != 0) {
                throw new IllegalStateException("平台业务失败 code=" + code + " msg=" + envelope.path("msg").asText());
            }
            return envelope.get("data");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("平台响应解析失败: " + truncate(response.body()), e);
        }
    }

    private URI uri(String path) {
        return URI.create(props.getPlatformBaseUrl() + path);
    }

    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }
}
