package com.swapops.sim.reporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.contract.DeviceSignature;
import com.swapops.sim.config.SimProperties;
import com.swapops.sim.config.TraceIds;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP 事件上报：单发送线程 + 有界队列保序（S3 升级队首持留/退避；当前为快速重试 + 显式丢弃告警）。
 */
@Slf4j
@Component
public class HttpEventReporter implements EventReporter {

    private static final int MAX_RETRY = 3;

    private final SimProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BlockingQueue<DeviceEventMessage> queue = new ArrayBlockingQueue<>(500);
    private final ExecutorService sender;
    private final RestTemplate restTemplate;

    public HttpEventReporter(SimProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restTemplate = new RestTemplate(factory);
        this.sender = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "swap-sim-event-sender");
            t.setDaemon(true);
            return t;
        });
        this.sender.submit(this::drainLoop);
    }

    @Override
    public void report(DeviceEventMessage message) {
        if (!queue.offer(message)) {
            DeviceEventMessage dropped = queue.poll();
            queue.offer(message);
            log.error("[事件缓冲溢出-丢弃最旧] cabinetNo={} eventSeq={}", dropped == null ? "?" : dropped.cabinetNo(),
                    dropped == null ? "?" : dropped.eventSeq());
        }
    }

    private void drainLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            DeviceEventMessage message;
            try {
                message = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            MDC.put(TraceIds.MDC_KEY, message.traceId());
            try {
                sendWithRetry(message);
            } finally {
                MDC.remove(TraceIds.MDC_KEY);
            }
        }
    }

    private void sendWithRetry(DeviceEventMessage message) {
        String secret = properties.secretOf(message.cabinetNo());
        if (secret == null || secret.isBlank()) {
            log.error("[事件上报取消] 柜密钥缺失 cabinetNo={}", message.cabinetNo());
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("cabinetNo", message.cabinetNo());
        body.put("eventType", message.eventType().name());
        body.put("cellNo", message.cellNo());
        body.put("batteryNo", message.batteryNo());
        body.put("soc", message.soc());
        body.put("commandSeq", message.commandSeq());
        body.put("bootId", message.bootId());
        body.put("eventSeq", message.eventSeq());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Device-No", message.cabinetNo());
        headers.set("X-Device-Sign", DeviceSignature.sign(secret, DeviceSignature.canonicalEvent(
                message.cabinetNo(), message.eventType(), message.cellNo(), message.batteryNo(),
                message.bootId(), message.eventSeq())));
        headers.set(TraceIds.HEADER, message.traceId());
        String url = properties.getServerBaseUrl() + "/device/event";
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
                log.info("[事件上报成功] cabinetNo={} eventType={} eventSeq={} commandSeq={}",
                        message.cabinetNo(), message.eventType(), message.eventSeq(), message.commandSeq());
                return;
            } catch (RestClientException e) {
                log.warn("[事件上报失败-第{}次] cabinetNo={} eventSeq={} cause={}",
                        attempt, message.cabinetNo(), message.eventSeq(), e.getMessage());
                sleep(500L * attempt);
            }
        }
        // S3 升级为队首持留等恢复；S1 显式丢弃（对账机制尚未上线，如实声明）
        log.error("[事件上报丢弃-重试耗尽] cabinetNo={} eventType={} eventSeq={}（S3 将持留重试）",
                message.cabinetNo(), message.eventType(), message.eventSeq());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        sender.shutdownNow();
    }
}
