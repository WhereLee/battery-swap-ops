package com.swapops.sim.reporter;

import com.swapops.contract.CabinetStatus;
import com.swapops.contract.DeviceSignature;
import com.swapops.sim.config.SimProperties;
import com.swapops.sim.config.TraceIds;
import com.swapops.sim.model.CabinetSim;
import com.swapops.sim.registry.SimRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 心跳上报：每柜独立节拍（并行池），失败不重试（下一轮即天然重试）；
 * 心跳恒 HTTP——判活不依赖任何消息中间件。
 */
@Slf4j
@Component
public class HeartbeatReporter {

    private final SimProperties properties;
    private final SimRegistry registry;
    private final RestTemplate restTemplate;
    private final ScheduledExecutorService scheduler;

    public HeartbeatReporter(SimProperties properties, SimRegistry registry) {
        this.properties = properties;
        this.registry = registry;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restTemplate = new RestTemplate(factory);
        this.scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "swap-sim-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void start() {
        long interval = properties.getHeartbeatIntervalSeconds();
        for (CabinetSim cabinet : registry.all()) {
            // 上电自述 + 周期心跳
            scheduler.submit(() -> report(cabinet));
            scheduler.scheduleAtFixedRate(() -> report(cabinet), interval, interval, TimeUnit.SECONDS);
        }
        log.info("心跳上报已启动：间隔 {}s，柜 {} 台 -> {}", interval, registry.size(),
                properties.getServerBaseUrl() + "/device/heartbeat");
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    private void report(CabinetSim cabinet) {
        String traceId = TraceIds.generate();
        MDC.put(TraceIds.MDC_KEY, traceId);
        try {
            String secret = properties.secretOf(cabinet.getCabinetNo());
            int status = CabinetStatus.ONLINE.getCode();
            Map<String, Object> body = Map.of("cabinetNo", cabinet.getCabinetNo(), "status", status);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Device-No", cabinet.getCabinetNo());
            headers.set("X-Device-Sign", DeviceSignature.sign(secret,
                    DeviceSignature.canonicalHeartbeat(cabinet.getCabinetNo(), status)));
            headers.set(TraceIds.HEADER, traceId);
            restTemplate.postForObject(properties.getServerBaseUrl() + "/device/heartbeat",
                    new HttpEntity<>(body, headers), Map.class);
            log.debug("[{}] 心跳上报 status={}", cabinet.getCabinetNo(), status);
        } catch (Exception e) {
            log.warn("[{}] 心跳上报失败 cause={}（下一轮重试）", cabinet.getCabinetNo(), e.getMessage());
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }
}
