package com.swapops.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.swapops.agent.config.AgentProperties;
import com.swapops.agent.model.AlarmView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台客户端测试（JDK HttpServer 假平台）：报文形态门禁（HTTP/1.1 + 定长 body + 幂等键头）、
 * 信封解析、业务/HTTP 错误语义、健康探测、token 缺失 fail-fast。
 */
class PlatformClientTest {

    private HttpServer server;
    private AgentProperties props;

    /** 0=正常 / 1=业务失败(code!=0) / 2=HTTP 401 */
    private final AtomicInteger mode = new AtomicInteger(0);
    private final AtomicReference<String> lastIdem = new AtomicReference<>();
    private final AtomicReference<String> lastContentLength = new AtomicReference<>();
    private final AtomicReference<String> lastTransferEncoding = new AtomicReference<>();
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", this::handle);
        server.start();
        props = new AgentProperties();
        props.setPlatformBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        props.setAdminToken("test-token");
        props.setEnabled(true);
        props.setTimeoutMs(3000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastToken.set(exchange.getRequestHeaders().getFirst("X-Admin-Token"));
        lastIdem.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        lastContentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
        lastTransferEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String path = exchange.getRequestURI().getPath();
        String body;
        int status = 200;
        if (mode.get() == 2) {
            status = 401;
            body = "{\"code\":401,\"msg\":\"管理端未认证\"}";
        } else if (path.endsWith("/actuator/health")) {
            body = "{\"status\":\"UP\"}";
        } else if (path.contains("/admin/agent-action")) {
            body = mode.get() == 1
                    ? "{\"code\":1,\"msg\":\"动作类型不在白名单\"}"
                    : "{\"code\":0,\"msg\":\"ok\",\"data\":{\"id\":9,\"actionNo\":\"AA9\",\"status\":1}}";
        } else if (path.contains("/admin/alarm")) {
            body = "{\"code\":0,\"msg\":\"ok\",\"data\":[{\"id\":5,\"deviceType\":\"CABINET\","
                    + "\"deviceNo\":\"SWAP-C-005\",\"alarmType\":\"CABINET_FAULT\",\"content\":\"fault\","
                    + "\"handled\":0,\"handler\":null,\"createTime\":1700000000000}]}";
        } else {
            status = 404;
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void listOpenAlarmsParsesViewAndSendsToken() {
        List<AlarmView> alarms = new PlatformClient(props).listOpenAlarms();
        assertThat(alarms).hasSize(1);
        assertThat(alarms.get(0).id()).isEqualTo(5);
        assertThat(alarms.get(0).deviceNo()).isEqualTo("SWAP-C-005");
        assertThat(alarms.get(0).alarmType()).isEqualTo("CABINET_FAULT");
        assertThat(lastToken.get()).isEqualTo("test-token");
    }

    @Test
    void proposeSendsFixedLengthBodyWithIdemKey() throws Exception {
        PlatformClient client = new PlatformClient(props);
        JsonNode data = client.propose("agent-5-create_work_order_from_alarm",
                Map.of("actionType", "CREATE_WORK_ORDER_FROM_ALARM", "reason", "[agent-auto] test"));
        assertThat(data.path("id").asLong()).isEqualTo(9);
        // 报文形态门禁：定长 body（Content-Length == UTF-8 字节数）且无 chunked
        assertThat(lastContentLength.get())
                .isEqualTo(String.valueOf(lastBody.get().getBytes(StandardCharsets.UTF_8).length));
        assertThat(lastTransferEncoding.get()).isNull();
        assertThat(lastIdem.get()).isEqualTo("agent-5-create_work_order_from_alarm");
        assertThat(lastBody.get()).contains("CREATE_WORK_ORDER_FROM_ALARM");
    }

    @Test
    void businessFailureThrows() {
        mode.set(1);
        assertThatThrownBy(() -> new PlatformClient(props).propose("k", Map.of("actionType", "X")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("code=1");
    }

    @Test
    void httpErrorThrows() {
        mode.set(2);
        assertThatThrownBy(() -> new PlatformClient(props).listOpenAlarms())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
    }

    @Test
    void healthUpTrueWhenReachableFalseWhenNot() {
        assertThat(new PlatformClient(props).healthUp()).isTrue();
        AgentProperties dead = new AgentProperties();
        dead.setPlatformBaseUrl("http://127.0.0.1:1/api");
        dead.setAdminToken("t");
        dead.setTimeoutMs(500);
        assertThat(new PlatformClient(dead).healthUp()).isFalse();
    }

    @Test
    void enabledWithoutTokenFailsFast() {
        AgentProperties noToken = new AgentProperties();
        noToken.setEnabled(true);
        noToken.setAdminToken("");
        assertThatThrownBy(() -> new PlatformClient(noToken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SWAP_AGENT_ADMIN_TOKEN");
    }
}
