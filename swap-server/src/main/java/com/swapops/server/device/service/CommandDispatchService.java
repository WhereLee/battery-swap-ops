package com.swapops.server.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.contract.CommandAction;
import com.swapops.contract.DeviceSignature;
import com.swapops.server.common.RRException;
import com.swapops.server.common.filter.TraceIdFilter;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 下行指令下发：seq 幂等键 → 流水记账 → HTTP 同步调用柜（连接复用 + 超时参数化）；
 * 柜拒绝/网络失败显式失败（流水 SEND_FAILED），不留假状态。
 */
@Slf4j
@Service
public class CommandDispatchService {

    private static final String CMD_PATH = "/cmd";
    private static final String DEVICE_HEADER = "X-Device-No";
    private static final String SIGN_HEADER = "X-Device-Sign";

    private final CabinetDao cabinetDao;
    private final DeviceChannelProperties properties;
    private final CommandLogService commandLogService;
    private final RestTemplate restTemplate;

    public CommandDispatchService(CabinetDao cabinetDao, DeviceChannelProperties properties,
                                  CommandLogService commandLogService) {
        this.cabinetDao = cabinetDao;
        this.properties = properties;
        this.commandLogService = commandLogService;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        this.restTemplate = new RestTemplate(factory);
    }

    public CommandLogEntity openCell(String cabinetNo, int cellNo) {
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo));
        if (cabinet == null) {
            throw new RRException("柜未登记: " + cabinetNo);
        }
        if (cabinet.getSecret() == null || cabinet.getSecret().isEmpty()) {
            throw new RRException("柜密钥缺失，拒绝下发: " + cabinetNo);
        }
        long seq = commandLogService.nextSeq(cabinetNo);
        // 链路号只取一次：流水与下行请求头必须同号（否则对账 grep 断链）
        String traceId = TraceIdFilter.currentOrGenerate();
        CommandLogEntity cmdLog = commandLogService.recordPending(cabinetNo, CommandAction.OPEN_CELL,
                seq, traceId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("cabinetNo", cabinetNo);
        payload.put("cellNo", cellNo);
        payload.put("commandSeq", seq);
        HttpHeaders headers = new HttpHeaders();
        headers.set(DEVICE_HEADER, cabinetNo);
        headers.set(SIGN_HEADER, DeviceSignature.sign(cabinet.getSecret(),
                DeviceSignature.canonicalCommand(cabinetNo, cellNo, seq)));
        headers.set(TraceIdFilter.TRACE_ID_HEADER, traceId);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(properties.getSimBaseUrl() + CMD_PATH,
                    new HttpEntity<>(payload, headers), Map.class);
            Object code = resp == null ? null : resp.get("code");
            if (resp == null || !Integer.valueOf(0).equals(code)) {
                Object msg = resp == null ? "空响应" : resp.get("msg");
                commandLogService.markSendFailed(cmdLog.getId());
                throw new RRException("柜拒绝指令: " + msg);
            }
            log.info("开仓指令已下发 cabinetNo={} cellNo={} seq={} -> 等待门开事件", cabinetNo, cellNo, seq);
            return cmdLog;
        } catch (HttpStatusCodeException e) {
            commandLogService.markSendFailed(cmdLog.getId());
            throw new RRException("柜拒绝指令(HTTP " + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            commandLogService.markSendFailed(cmdLog.getId());
            throw new RRException("柜无响应(连接失败): " + cabinetNo + " cause=" + e.getMessage());
        }
    }
}
