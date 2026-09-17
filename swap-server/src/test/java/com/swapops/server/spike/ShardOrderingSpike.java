package com.swapops.server.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.contract.DeviceSignature;
import com.swapops.contract.EventType;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * P0-2 分片保序剧本注入器（本地手动跑，CI 不触发——类名不匹配 surefire 的 *Test include）。
 *
 * <p><b>运行</b>：{@code mvn -pl swap-server test -Dtest=ShardOrderingSpike}
 * <p><b>前置</b>：① 平台以 MQ 通道在跑（swap.device.mq.enabled=true，8400）；② RocketMQ proxy 127.0.0.1:8081；
 * ③ topic 已迁 FIFO（{@code mqadmin updateTopic -c DefaultCluster -t swap-device-event -a +message.type=FIFO}）；
 * ④ 环境变量 SWAP_DEV_SECRET（事件签名，零明文）；⑤ sim 已停（避免 HTTP 通道事件干扰台账断言）。
 *
 * <p><b>三场景</b>（事件类型用 DOOR_CLOSED：只推进设备台账，不动订单/仓/电池——纯序守卫验证）：
 * <ol>
 *   <li>S1 同柜乱序：seq=100005 先到、seq=100000 后到（同 bootId）→ 台账停在 100005（旧序被序守卫拒）；</li>
 *   <li>S2 跨代重放：新代际推到 100150 → 用"已见旧 bootId"发更大 seq 100200 → 台账/bootId 不回退（代际守卫拒）；</li>
 *   <li>S3 多柜并发：两柜各 20 条递增事件交错注入 → 每柜台账都到预期且 bootId 各自独立（跨柜并行、不串柜、不丢序）。</li>
 * </ol>
 * <b>断言口径</b>：{@code GET /api/dev/device/cabinet?cabinetNo=...} 的 lastEventSeq/lastBootId（平台真值）。
 * 跨柜并行的线程级证据由 PowerShell 剧本从平台日志的 worker 线程名断言（platform-mq-worker-N）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShardOrderingSpike {

    private static final String MQ_ENDPOINT = "127.0.0.1:8081";
    private static final String TOPIC = "swap-device-event";
    private static final String PLATFORM = "http://127.0.0.1:8400/api";
    private static final String CAB_A = "SWAP-C-003";
    private static final String CAB_B = "SWAP-C-004";
    private static final int CELL_NO = 1;

    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private String secret;
    private Producer producer;

    @BeforeEach
    void setUp() throws Exception {
        secret = System.getenv("SWAP_DEV_SECRET");
        Assertions.assertNotNull(secret, "前置缺失：环境变量 SWAP_DEV_SECRET（事件签名用，零明文）");
        Assertions.assertFalse(secret.isBlank(), "前置缺失：SWAP_DEV_SECRET 为空");
        producer = provider.newProducerBuilder()
                .setClientConfiguration(ClientConfiguration.newBuilder().setEndpoints(MQ_ENDPOINT).build())
                .setTopics(TOPIC)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception ignore) {
                // 关闭异常不影响结论
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("S1 同柜乱序：旧 seq 后到被序守卫拒（台账不回退）")
    void s1SameCabinetOutOfOrder() throws Exception {
        String boot = "spike-s1-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long base = baseFor(CAB_A);
        send(CAB_A, boot, base + 5);
        awaitSeq(CAB_A, base + 5, 15_000);
        System.out.println("[S1] 新序 " + (base + 5) + " 已受理，台账=" + ledger(CAB_A).get("lastEventSeq"));

        send(CAB_A, boot, base);      // 同代际旧序（乱序注入）
        Thread.sleep(4000);           // 给消费端处理窗口
        JsonNode after = ledger(CAB_A);
        System.out.println("[S1] 旧序 " + base + " 注入后台账=" + after.get("lastEventSeq")
                + " bootId=" + after.get("lastBootId").asText());
        Assertions.assertEquals(base + 5, after.get("lastEventSeq").asLong(),
                "旧序事件必须被拒：台账停在 " + (base + 5) + "（分片后柜内序仍由序守卫守住）");
        Assertions.assertEquals(boot, after.get("lastBootId").asText(), "代际不应变化");
        System.out.println("[S1] PASS：同柜乱序被拒，柜内保序不变量成立（旧序 seq=" + base + "）");
    }

    @Test
    @Order(2)
    @DisplayName("S2 跨代重放：已见旧 bootId 携更大 seq 仍被拒（代际守卫）")
    void s2CrossBootReplay() throws Exception {
        String oldBoot = "spike-s2-old-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String newBoot = "spike-s2-new-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long base = baseFor(CAB_A);

        send(CAB_A, oldBoot, base);
        awaitSeq(CAB_A, base, 15_000);
        send(CAB_A, newBoot, base + 50);   // 代际推进
        awaitSeq(CAB_A, base + 50, 15_000);
        System.out.println("[S2] 代际已推进到 " + newBoot + "，台账=" + (base + 50));

        send(CAB_A, oldBoot, base + 100);  // 跨代重放：旧代际 + 更大 seq（序守卫会放行，代际守卫必须拦）
        Thread.sleep(4000);
        JsonNode after = ledger(CAB_A);
        System.out.println("[S2] 跨代重放（" + oldBoot + " seq=" + (base + 100) + "）注入后台账="
                + after.get("lastEventSeq") + " bootId=" + after.get("lastBootId").asText());
        Assertions.assertEquals(base + 50, after.get("lastEventSeq").asLong(),
                "跨代重放必须被拒：台账不回退也不被旧代际推进");
        Assertions.assertEquals(newBoot, after.get("lastBootId").asText(),
                "当前代际必须保持（旧代际不得复辟）");
        System.out.println("[S2] PASS：跨代重放被拒（两层守卫互补：序守卫按代际放行，代际守卫拦历史重放）");
    }

    @Test
    @Order(3)
    @DisplayName("S3 多柜并发：两柜各 20 条交错注入，各自到预期且不串柜")
    void s3MultiCabinetConcurrent() throws Exception {
        String bootA = "spike-s3-a-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String bootB = "spike-s3-b-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long base = baseFor(CAB_A, CAB_B);
        int events = 20;

        // 交错发送：同批消息里两柜混合到达 → 消费端按柜分发（同柜串行、跨柜并行）
        for (int i = 0; i < events; i++) {
            send(CAB_A, bootA, base + i);
            send(CAB_B, bootB, base + i);
        }
        awaitSeq(CAB_A, base + events - 1, 30_000);
        awaitSeq(CAB_B, base + events - 1, 30_000);

        JsonNode a = ledger(CAB_A);
        JsonNode b = ledger(CAB_B);
        System.out.println("[S3] " + CAB_A + " 台账=" + a.get("lastEventSeq") + " bootId=" + a.get("lastBootId").asText());
        System.out.println("[S3] " + CAB_B + " 台账=" + b.get("lastEventSeq") + " bootId=" + b.get("lastBootId").asText());
        Assertions.assertEquals(base + events - 1, a.get("lastEventSeq").asLong(), CAB_A + " 应无丢序");
        Assertions.assertEquals(base + events - 1, b.get("lastEventSeq").asLong(), CAB_B + " 应无丢序");
        Assertions.assertEquals(bootA, a.get("lastBootId").asText(), CAB_A + " 代际不串柜");
        Assertions.assertEquals(bootB, b.get("lastBootId").asText(), CAB_B + " 代际不串柜");
        System.out.println("[S3] PASS：两柜各 " + events + " 条并发注入全部受理（base=" + base
                + "），柜内单调、柜间不串（跨柜并行成立）");
    }

    // ---------- 辅助 ----------

    /** 发一条 DOOR_CLOSED 事件（带 FIFO message group=柜号，签名与 sim 同一 canonical） */
    private void send(String cabinetNo, String bootId, long eventSeq) throws Exception {
        String body = String.format(
                "{\"cabinetNo\":\"%s\",\"eventType\":\"%s\",\"cellNo\":%d,\"bootId\":\"%s\",\"eventSeq\":%d}",
                cabinetNo, EventType.DOOR_CLOSED.name(), CELL_NO, bootId, eventSeq);
        String signature = DeviceSignature.sign(secret, DeviceSignature.canonicalEvent(
                cabinetNo, EventType.DOOR_CLOSED, CELL_NO, null, bootId, eventSeq));
        Message message = provider.newMessageBuilder()
                .setTopic(TOPIC)
                .setMessageGroup(cabinetNo)
                .setKeys(cabinetNo + "-" + eventSeq)
                .addProperty("X-Device-No", cabinetNo)
                .addProperty("X-Device-Sign", signature)
                .addProperty("traceId", "spike-" + cabinetNo + "-" + eventSeq)
                .setBody(body.getBytes(StandardCharsets.UTF_8))
                .build();
        producer.send(message);
    }

    /** 平台真值：dev 端点的柜台账（lastEventSeq/lastBootId） */
    private JsonNode ledger(String cabinetNo) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(PLATFORM + "/dev/device/cabinet?cabinetNo=" + cabinetNo))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, response.statusCode(), "dev 台账端点应 200");
        JsonNode root = objectMapper.readTree(response.body());
        Assertions.assertEquals(0, root.get("code").asInt(), "dev 台账端点应 code=0：" + response.body());
        return root.get("data");
    }

    /** 轮询等待台账推进到预期 seq（消费延迟容忍） */
    private void awaitSeq(String cabinetNo, long expected, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        long last = -1;
        while (System.currentTimeMillis() < deadline) {
            last = ledger(cabinetNo).get("lastEventSeq").asLong();
            if (last >= expected) {
                return;
            }
            Thread.sleep(300);
        }
        Assertions.fail("等待台账推进超时：" + cabinetNo + " 期望 ≥" + expected + " 实际=" + last);
    }

    /** 动态基线：每轮基于当前台账推进（剧本可重复跑、幂等），并避开历史区间 */
    private long baseFor(String... cabinets) throws Exception {
        long max = 0;
        for (String cabinetNo : cabinets) {
            max = Math.max(max, ledger(cabinetNo).get("lastEventSeq").asLong());
        }
        return Math.max(100000L, max + 1000L);
    }
}
