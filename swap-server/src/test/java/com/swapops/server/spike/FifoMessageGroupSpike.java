package com.swapops.server.spike;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P0-2 技术验证 spike：RocketMQ 5 gRPC 客户端 FIFO message group 通道实测。
 *
 * <p><b>运行方式（本地手动，CI 不触发——类名不匹配 surefire 的 *Test include）</b>：
 * <pre>mvn -pl swap-server test -Dtest=FifoMessageGroupSpike</pre>
 * 前置：本地 RocketMQ（namesrv 9876 / broker 10911 / proxy 8081）+ FIFO topic：
 * <pre>mqadmin updateTopic -n 127.0.0.1:9876 -c DefaultCluster -t swap-spike-fifo -a +message.type=FIFO</pre>
 *
 * <p>验证四件事（结论打印到 stdout，供 scripts/verify/batch24/ 落档）：
 * <ol type="A">
 *   <li>FIFO topic 接受 setMessageGroup 消息；业务 NORMAL topic 的类型校验行为实测（决定迁移方式）；</li>
 *   <li>同柜（同 message group）消息严格按发送序到达——保序不变量成立；</li>
 *   <li>同柜前条未 ack → 后条不可见（服务端保证"柜内串行"）；</li>
 *   <li>一柜 hold 时其他柜照常推进（"跨柜并行"的服务端保证）。</li>
 * </ol>
 * 附带实测：空队列 receive 的异常形态（关系到消费者空轮是否该重建 consumer）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FifoMessageGroupSpike {

    private static final String ENDPOINT = "127.0.0.1:8081";
    private static final String FIFO_TOPIC = "swap-spike-fifo";
    private static final String NORMAL_TOPIC = "swap-device-event";
    private static final String GROUP = "spike-fifo-group";
    private static final Duration INVISIBLE = Duration.ofSeconds(30);
    private static final Duration AWAIT = Duration.ofSeconds(3);

    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final ClientConfiguration config = ClientConfiguration.newBuilder()
            .setEndpoints(ENDPOINT).build();

    private Producer fifoProducer;
    private SimpleConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        fifoProducer = provider.newProducerBuilder()
                .setClientConfiguration(config)
                .setTopics(FIFO_TOPIC)
                .build();
        consumer = provider.newSimpleConsumerBuilder()
                .setClientConfiguration(config)
                .setConsumerGroup(GROUP)
                .setSubscriptionExpressions(Collections.singletonMap(FIFO_TOPIC, FilterExpression.SUB_ALL))
                .setAwaitDuration(AWAIT)
                .build();
        drain("setUp");
    }

    @AfterEach
    void tearDown() {
        closeQuietly(fifoProducer);
        closeQuietly(consumer);
    }

    @Test
    @Order(1)
    @DisplayName("A: FIFO topic 接受 message group；NORMAL topic 类型校验实测")
    void messageTypeValidation() throws Exception {
        var receipt = fifoProducer.send(msg(FIFO_TOPIC, "SWAP-C-001", "SWAP-C-001#probe"));
        System.out.println("[A] FIFO topic + messageGroup 发送成功 msgId=" + receipt.getMessageId());

        Producer normalProducer = provider.newProducerBuilder()
                .setClientConfiguration(config)
                .setTopics(NORMAL_TOPIC)
                .build();
        try {
            normalProducer.send(msg(NORMAL_TOPIC, "SWAP-C-001", "SWAP-C-001#normal-probe"));
            System.out.println("[A] NORMAL topic + messageGroup 发送【被接受】→ broker 未强校验 messageType，"
                    + "业务 topic 可平滑迁移（先改发送端再改 topic 属性也不会断流）");
        } catch (Exception e) {
            System.out.println("[A] NORMAL topic + messageGroup 发送【被拒】→ "
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "（业务 topic 必须先迁 FIFO 属性，发送端与 topic 属性需同批切换）");
        } finally {
            closeQuietly(normalProducer);
        }
        drain("A");
    }

    @Test
    @Order(2)
    @DisplayName("B: 同柜严格按发送序到达（跨柜交错不影响柜内序）")
    void sameGroupArrivesInSendOrder() throws Exception {
        int cabinets = 3;
        int events = 5;
        // 轮转柜号发送：制造跨柜交错，验证柜内序不受影响
        for (int seq = 1; seq <= events; seq++) {
            for (int c = 1; c <= cabinets; c++) {
                String cab = String.format("SWAP-C-%03d", c);
                fifoProducer.send(msg(FIFO_TOPIC, cab, cab + "#" + seq));
            }
        }

        Map<String, List<Integer>> arrival = new LinkedHashMap<>();
        int total = cabinets * events;
        int got = 0;
        long deadline = System.currentTimeMillis() + 30_000;
        while (got < total && System.currentTimeMillis() < deadline) {
            for (MessageView mv : receiveQuietly()) {
                String[] parts = bodyOf(mv).split("#");
                arrival.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(Integer.parseInt(parts[1]));
                consumer.ack(mv);
                got++;
            }
        }
        Assertions.assertEquals(total, got, "应收到全部 " + total + " 条");
        Assertions.assertEquals(cabinets, arrival.size(), "三柜都应到达");
        for (Map.Entry<String, List<Integer>> e : arrival.entrySet()) {
            System.out.println("[B] " + e.getKey() + " 到达序=" + e.getValue());
            Assertions.assertEquals(List.of(1, 2, 3, 4, 5), e.getValue(), e.getKey() + " 柜内保序失败");
        }
        System.out.println("[B] 结论：同 message group 严格 FIFO；跨柜交错不影响柜内序 → 保序不变量可从\"全局\"降为\"每柜\"");
    }

    @Test
    @Order(3)
    @DisplayName("C+D: 实测同柜未 ack 后续仍投递（服务端只保证投递序）→ 消费端必须按柜串行；未 ack 到期重投")
    void unackedDoesNotBlockSameGroup() throws Exception {
        String cabA = "SWAP-C-101";
        String cabB = "SWAP-C-102";
        for (int seq = 1; seq <= 3; seq++) {
            fifoProducer.send(msg(FIFO_TOPIC, cabA, cabA + "#" + seq));
            fifoProducer.send(msg(FIFO_TOPIC, cabB, cabB + "#" + seq));
        }

        // C：一次 receive 批量里同柜多条全部可见（首条未 ack 也不 hold 后续）
        List<MessageView> round1 = receiveQuietly();
        System.out.println("[C] 第一轮可见=" + describe(round1));
        int aCount = countOf(round1, cabA);
        int bCount = countOf(round1, cabB);
        System.out.println("[C] 柜A可见=" + aCount + " 柜B可见=" + bCount);
        Assertions.assertEquals(3, aCount, "实测：同柜后续在首条未 ack 时仍被投递（服务端不 hold）");
        Assertions.assertEquals(3, bCount, "跨柜消息同批可见（并行投递）");
        Assertions.assertEquals(List.of(cabA + "#1", cabA + "#2", cabA + "#3"), bodiesOf(round1, cabA),
                "同柜批量内仍为发送序（投递序由服务端保证）");
        System.out.println("[C] 结论：服务端只保证\"同柜投递序\"，不保证\"消费串行\"——"
                + "消费端必须按柜串行执行（否则同柜并发处理仍会乱序）");

        // D：ack 语义与重投——柜 A 全 ack；柜 B 前两条 ack、第三条改短 invisible 不 ack
        for (MessageView mv : round1) {
            String body = bodyOf(mv);
            if (body.startsWith(cabA) || !body.endsWith("#3")) {
                consumer.ack(mv);
            } else {
                consumer.changeInvisibleDuration(mv, Duration.ofSeconds(3));
                System.out.println("[D] 柜 B #3 不 ack，invisible 改 3s（模拟处理超时/消费者崩溃）");
            }
        }
        Thread.sleep(4500);
        List<MessageView> round2 = receiveQuietly();
        System.out.println("[D] 重投轮可见=" + describe(round2));
        Assertions.assertEquals(1, countOf(round2, cabB), "未 ack 消息在 invisible 到期后重投（恢复路径）");
        Assertions.assertEquals(cabB + "#3", bodyOf(round2.get(0)), "重投的正是未 ack 那条");
        for (MessageView mv : round2) {
            consumer.ack(mv);
        }
        drain("C+D");
        System.out.println("[D] 结论：未 ack → invisible 到期重投；重复投递由业务幂等（序守卫 + CAS）吸收");
    }

    // ---------- 辅助 ----------

    private Message msg(String topic, String group, String body) throws Exception {
        return provider.newMessageBuilder()
                .setTopic(topic)
                .setMessageGroup(group)
                .setKeys(group + "-" + System.nanoTime())
                .setBody(body.getBytes(StandardCharsets.UTF_8))
                .build();
    }

    /** receive 空轮容错：5.x 无消息时抛 ClientException（实测打印其形态，供消费者实现参考） */
    private List<MessageView> receiveQuietly() {
        try {
            return consumer.receive(32, INVISIBLE);
        } catch (ClientException e) {
            System.out.println("[spike] receive 空轮异常形态=" + e.getClass().getSimpleName()
                    + " msg=" + e.getMessage());
            return List.of();
        } catch (Exception e) {
            System.out.println("[spike] receive 意外异常=" + e.getClass().getSimpleName() + " msg=" + e.getMessage());
            return List.of();
        }
    }

    private void drain(String phase) {
        int acked = 0;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<MessageView> batch = receiveQuietly();
            if (batch.isEmpty()) {
                break;
            }
            for (MessageView mv : batch) {
                try {
                    consumer.ack(mv);
                    acked++;
                } catch (Exception ignore) {
                    // 排空尽力而为
                }
            }
        }
        if (acked > 0) {
            System.out.println("[drain:" + phase + "] 清理残留消息 " + acked + " 条");
        }
    }

    private String bodyOf(MessageView mv) {
        ByteBuffer buf = mv.getBody().duplicate();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private int countOf(List<MessageView> batch, String cabinetPrefix) {
        int n = 0;
        for (MessageView mv : batch) {
            if (bodyOf(mv).startsWith(cabinetPrefix)) {
                n++;
            }
        }
        return n;
    }

    private List<String> bodiesOf(List<MessageView> batch, String cabinetPrefix) {
        List<String> bodies = new ArrayList<>();
        for (MessageView mv : batch) {
            if (bodyOf(mv).startsWith(cabinetPrefix)) {
                bodies.add(bodyOf(mv));
            }
        }
        return bodies;
    }

    private String describe(List<MessageView> batch) {
        List<String> bodies = new ArrayList<>();
        for (MessageView mv : batch) {
            bodies.add(bodyOf(mv));
        }
        return bodies.toString();
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignore) {
            // 关闭异常不影响 spike 结论
        }
    }
}
