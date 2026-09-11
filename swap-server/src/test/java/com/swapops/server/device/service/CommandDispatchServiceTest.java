package com.swapops.server.device.service;

import com.swapops.contract.CommandAction;
import com.swapops.contract.DeviceSignature;
import com.swapops.server.common.RRException;
import com.swapops.server.common.filter.TraceIdFilter;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下行指令单测（JDK HttpServer 真实回环）：成功销账等待、柜拒绝显式失败、网络失败显式失败。
 */
@DisplayName("下行指令下发（协议 v1）")
@ExtendWith(MockitoExtension.class)
class CommandDispatchServiceTest {

    private static final String CABINET_NO = "SWAP-C-001";
    private static final String SECRET = "aabbccddeeff00112233445566778899";

    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private CommandLogService commandLogService;

    private DeviceChannelProperties properties;
    private CommandDispatchService service;
    private HttpServer server;
    private final AtomicReference<String> lastSign = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile String responseBody = "{\"code\":0,\"msg\":\"已受理\"}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastSign.set(exchange.getRequestHeaders().getFirst("X-Device-Sign"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        properties = new DeviceChannelProperties();
        properties.setSimBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeoutMillis(1000);
        properties.setReadTimeoutMillis(1000);
        // 单测用宽松护栏（默认窗口 100 次，不会触发熔断）；护栏行为另有 DeviceDownlinkGuardTest 覆盖
        com.swapops.server.common.resilience.DeviceDownlinkGuard guard =
                new com.swapops.server.common.resilience.DeviceDownlinkGuard(
                        io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults(),
                        io.github.resilience4j.bulkhead.BulkheadRegistry.ofDefaults());
        service = new CommandDispatchService(cabinetDao, properties, commandLogService, guard);
    }

    /** 已登记柜 + seq/流水桩（仅需要走完整下发链路的用例调用，避免 UnnecessaryStubbing） */
    private void stubRegisteredCabinet() {
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setCabinetNo(CABINET_NO);
        cabinet.setSecret(SECRET);
        when(cabinetDao.selectOne(any())).thenReturn(cabinet);
        when(commandLogService.nextSeq(CABINET_NO)).thenReturn(7L);
        CommandLogEntity log = new CommandLogEntity();
        log.setId(100L);
        log.setCommandSeq(7L);
        when(commandLogService.recordPending(eq(CABINET_NO), eq(CommandAction.OPEN_CELL), eq(7L), anyString()))
                .thenReturn(log);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        MDC.clear();
    }

    @Test
    @DisplayName("开仓成功：携正确 HMAC 签名 + 报文（seq 幂等键），流水等待事件销账")
    void 开仓成功_签名与报文() {
        stubRegisteredCabinet();

        CommandLogEntity log = service.openCell(CABINET_NO, 3);

        assertThat(log.getCommandSeq()).isEqualTo(7L);
        assertThat(lastSign.get()).isEqualTo(
                DeviceSignature.sign(SECRET, DeviceSignature.canonicalCommand(CABINET_NO, 3, 7L)));
        assertThat(lastBody.get()).contains("\"cellNo\":3").contains("\"commandSeq\":7");
    }

    @Test
    @DisplayName("柜拒绝（code=1）：流水 SEND_FAILED + 显式抛出")
    void 柜拒绝_失败显式化() {
        stubRegisteredCabinet();
        responseBody = "{\"code\":1,\"msg\":\"空仓无电池\"}";

        assertThatThrownBy(() -> service.openCell(CABINET_NO, 3))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("柜拒绝指令");
        verify(commandLogService).markSendFailed(100L);
    }

    @Test
    @DisplayName("网络不可达：流水 SEND_FAILED + 无响应语义")
    void 网络失败_记失败() {
        stubRegisteredCabinet();
        properties.setSimBaseUrl("http://127.0.0.1:1");

        assertThatThrownBy(() -> service.openCell(CABINET_NO, 3))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("柜无响应");
        verify(commandLogService).markSendFailed(100L);
    }

    @Test
    @DisplayName("柜未登记：拒绝下发，不分配 seq")
    void 未登记柜_拒绝() {
        when(cabinetDao.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.openCell("SWAP-C-999", 3))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("柜未登记");
    }
}
