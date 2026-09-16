package com.swapops.server.alarm.service;

import com.swapops.server.alarm.config.AlarmWebhookProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-11：webhook 出站通知——签名 / 重试 / 禁用 / 熔断（JDK HttpServer 假接收端，零外部依赖）。
 */
@DisplayName("告警出站 webhook（P1-11）")
class AlarmWebhookNotifierTest {

    private static final String SECRET = "unit-test-secret";

    private HttpServer server;
    private int port;
    private volatile int responseStatus = 200;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> signs = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            requestCount.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            signs.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Swap-Sign")));
            events.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Swap-Event")));
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private AlarmWebhookProperties props() {
        AlarmWebhookProperties p = new AlarmWebhookProperties();
        p.setUrl("http://127.0.0.1:" + port + "/hook");
        p.setSecret(SECRET);
        p.setMaxAttempts(3);
        p.setBackoffBaseMillis(10);
        p.setConnectTimeoutMillis(500);
        p.setRequestTimeoutMillis(800);
        return p;
    }

    private static void await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).as("等待条件超时(%dms)", timeoutMillis).isTrue();
    }

    @Test
    @DisplayName("投递成功：body / X-Swap-Event / HMAC 签名头正确，成功计数 +1")
    void 投递成功且签名正确() throws Exception {
        AlarmWebhookNotifier notifier = new AlarmWebhookNotifier(props());
        String body = "{\"alarmId\":7,\"eventKind\":\"RAISED\"}";
        notifier.notify("RAISED", body);
        await(() -> notifier.sentCount() == 1, 3000);

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(bodies.get(0)).isEqualTo(body);
        assertThat(events.get(0)).isEqualTo("RAISED");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        assertThat(signs.get(0)).isEqualTo(expected);
        notifier.shutdown();
    }

    @Test
    @DisplayName("接收端 5xx：重试 3 次后计失败，成功计数保持 0")
    void 非2xx重试耗尽() throws Exception {
        responseStatus = 500;
        AlarmWebhookNotifier notifier = new AlarmWebhookNotifier(props());
        notifier.notify("RAISED", "{\"alarmId\":1}");
        await(() -> notifier.failedCount() == 1, 5000);

        assertThat(requestCount.get()).isEqualTo(3);
        assertThat(notifier.sentCount()).isZero();
        notifier.shutdown();
    }

    @Test
    @DisplayName("url 为空：整体禁用，不发请求不计丢弃")
    void 未配置禁用() throws Exception {
        AlarmWebhookProperties p = props();
        p.setUrl("");
        AlarmWebhookNotifier notifier = new AlarmWebhookNotifier(p);
        notifier.notify("RAISED", "{\"alarmId\":1}");
        Thread.sleep(150);

        assertThat(requestCount.get()).isZero();
        assertThat(notifier.sentCount()).isZero();
        assertThat(notifier.droppedCount()).isZero();
        notifier.shutdown();
    }

    @Test
    @DisplayName("连续失败达阈值：熔断打开后新事件直接丢弃（计数+1，不再发请求）")
    void 熔断打开() throws Exception {
        responseStatus = 500;
        AlarmWebhookProperties p = props();
        p.setMaxAttempts(1);
        p.setCircuitFailThreshold(2);
        p.setCircuitOpenMillis(60_000);
        AlarmWebhookNotifier notifier = new AlarmWebhookNotifier(p);

        notifier.notify("RAISED", "{\"alarmId\":1}");
        notifier.notify("HANDLED", "{\"alarmId\":1}");
        await(() -> notifier.failedCount() == 2, 5000);
        Thread.sleep(100); // 等 deliver 尾部熔断状态落定

        notifier.notify("RECOVERED", "{\"alarmId\":1}");
        assertThat(notifier.droppedCount()).isEqualTo(1);
        Thread.sleep(150);
        assertThat(requestCount.get()).isEqualTo(2);
        notifier.shutdown();
    }
}
