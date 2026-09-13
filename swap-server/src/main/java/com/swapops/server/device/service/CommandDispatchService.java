package com.swapops.server.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.contract.CommandAction;
import com.swapops.contract.DeviceSignature;
import com.swapops.server.common.RRException;
import com.swapops.server.common.filter.TraceIdFilter;
import com.swapops.server.common.resilience.DeviceDownlinkGuard;
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
 * 下行指令下发（两段式）：
 * <ul>
 *   <li>{@link #prepareOpen}：分配 seq 幂等键 + 记流水 PENDING（短事务，便于订单先绑定 seq 再发包）；</li>
 *   <li>{@link #dispatchPrepared}：HTTP 同步下发；柜拒绝/网络失败显式化（流水 SEND_FAILED）。</li>
 * </ul>
 * 事务外发送（订单域保证）：不在 DB 事务里占据网络等待；失败由调用方补偿。
 */
@Slf4j
@Service
public class CommandDispatchService {

    private static final String CMD_PATH = "/cmd";
    private static final String QUERY_PATH = "/cmd/query";
    private static final String DEVICE_HEADER = "X-Device-No";
    private static final String SIGN_HEADER = "X-Device-Sign";

    private final CabinetDao cabinetDao;
    private final DeviceChannelProperties properties;
    private final CommandLogService commandLogService;
    private final RestTemplate restTemplate;
    private final DeviceDownlinkGuard downlinkGuard;

    public CommandDispatchService(CabinetDao cabinetDao, DeviceChannelProperties properties,
                                  CommandLogService commandLogService, DeviceDownlinkGuard downlinkGuard) {
        this.cabinetDao = cabinetDao;
        this.properties = properties;
        this.commandLogService = commandLogService;
        this.downlinkGuard = downlinkGuard;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        this.restTemplate = new RestTemplate(factory);
    }

    /** 准备一次开仓指令：校验柜/密钥 → seq → 流水 PENDING（未发包） */
    public CommandLogEntity prepareOpen(String cabinetNo, int cellNo, String traceId) {
        requireDispatchable(cabinetNo);
        long seq = commandLogService.nextSeq(cabinetNo);
        return commandLogService.recordPending(cabinetNo, CommandAction.OPEN_CELL, seq, traceId);
    }

    /** 下发已准备的指令（同步 HTTP；失败记 SEND_FAILED 并抛业务异常） */
    public void dispatchPrepared(CommandLogEntity cmdLog, String cabinetNo, int cellNo) {
        dispatchPrepared(cmdLog, cabinetNo, cellNo, true);
    }

    /**
     * 下发已准备的指令。
     *
     * @param terminalOnFailure true=失败记 SEND_FAILED（业务下发语义）；
     *                          false=失败不记终态（对账重试语义：由重试分层决定去留）
     */
    public void dispatchPrepared(CommandLogEntity cmdLog, String cabinetNo, int cellNo,
                                 boolean terminalOnFailure) {
        CabinetEntity cabinet = requireDispatchable(cabinetNo);
        Map<String, Object> payload = new HashMap<>();
        payload.put("cabinetNo", cabinetNo);
        payload.put("cellNo", cellNo);
        payload.put("commandSeq", cmdLog.getCommandSeq());
        HttpHeaders headers = new HttpHeaders();
        headers.set(DEVICE_HEADER, cabinetNo);
        headers.set(SIGN_HEADER, DeviceSignature.sign(cabinet.getSecret(),
                DeviceSignature.canonicalCommand(cabinetNo, cellNo, cmdLog.getCommandSeq())));
        headers.set(TraceIdFilter.TRACE_ID_HEADER, cmdLog.getTraceId());
        try {
            // 熔断/舱壁：设备故障时快速失败（fallback 不执行动作，返回哨兵 code 走拒绝分支）
            Map<String, Object> resp = downlinkGuard.call(
                    () -> postJson(properties.getSimBaseUrl() + CMD_PATH, payload, headers),
                    () -> Map.of("code", -1, "msg", "设备通道熔断/过载（快速失败）"));
            Object code = resp == null ? null : resp.get("code");
            if (resp == null || !Integer.valueOf(0).equals(code)) {
                Object msg = resp == null ? "空响应" : resp.get("msg");
                if (terminalOnFailure) {
                    commandLogService.markSendFailed(cmdLog.getId());
                }
                throw new RRException("柜拒绝指令: " + msg);
            }
            log.info("开仓指令已下发 cabinetNo={} cellNo={} seq={} -> 等待门开事件",
                    cabinetNo, cellNo, cmdLog.getCommandSeq());
        } catch (HttpStatusCodeException e) {
            if (terminalOnFailure) {
                commandLogService.markSendFailed(cmdLog.getId());
            }
            throw new RRException("柜拒绝指令(HTTP " + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            if (terminalOnFailure) {
                commandLogService.markSendFailed(cmdLog.getId());
            }
            throw new RRException("柜无响应(连接失败): " + cabinetNo + " cause=" + e.getMessage());
        }
    }

    /** 实况快照查询结果（QUERY_STATE 对账，S3.2） */
    public record QueryResult(Integer state, Map<Integer, CellSnapshot> cells,
                              String bootId, Long eventSeq, Long lastCommandSeq) {
    }

    /** 单仓快照 */
    public record CellSnapshot(int cellNo, boolean hasBattery, String batteryNo, Integer soc) {
    }

    /**
     * 查询设备实况（QUERY_STATE，canonicalQuery 签名）：失败（无响应/被拒/应答缺字段）返回 null，
     * 与"查到非目标态"区分（调用方决策不同：前者走重试，后者按实况仲裁）。
     */
    public QueryResult queryState(String cabinetNo) {
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo));
        if (cabinet == null || cabinet.getSecret() == null || cabinet.getSecret().isEmpty()) {
            log.warn("状态查询取消：柜未登记或密钥缺失 cabinetNo={}", cabinetNo);
            return null;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("cabinetNo", cabinetNo);
        HttpHeaders headers = new HttpHeaders();
        headers.set(DEVICE_HEADER, cabinetNo);
        headers.set(SIGN_HEADER, DeviceSignature.sign(cabinet.getSecret(),
                DeviceSignature.canonicalQuery(cabinetNo)));
        headers.set(TraceIdFilter.TRACE_ID_HEADER, TraceIdFilter.currentOrGenerate());
        try {
            // 熔断打开/舱壁满：返回 null（与"查询失败"同语义 → 对账任务下轮再试，不阻断业务）
            Map<String, Object> resp = downlinkGuard.call(
                    () -> postJson(properties.getSimBaseUrl() + QUERY_PATH, payload, headers),
                    () -> null);
            if (resp == null || !Integer.valueOf(0).equals(resp.get("code"))) {
                log.warn("状态查询被拒 cabinetNo={} resp={}", cabinetNo, resp);
                return null;
            }
            Object dataObj = resp.get("data");
            if (!(dataObj instanceof Map<?, ?> data) || data.get("state") == null) {
                log.warn("状态查询应答缺 data/state cabinetNo={}", cabinetNo);
                return null;
            }
            Map<Integer, CellSnapshot> cells = new HashMap<>();
            if (data.get("cells") instanceof Map<?, ?> cellViews) {
                for (Map.Entry<?, ?> entry : cellViews.entrySet()) {
                    int cellNo;
                    try {
                        cellNo = Integer.parseInt(String.valueOf(entry.getKey()));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (entry.getValue() instanceof Map<?, ?> cv) {
                        boolean hasBattery = Boolean.TRUE.equals(cv.get("hasBattery"));
                        String batteryNo = cv.get("batteryNo") == null ? null : String.valueOf(cv.get("batteryNo"));
                        Integer soc = cv.get("soc") instanceof Number n ? n.intValue() : null;
                        cells.put(cellNo, new CellSnapshot(cellNo, hasBattery, batteryNo, soc));
                    }
                }
            }
            QueryResult result = new QueryResult(
                    ((Number) data.get("state")).intValue(),
                    cells,
                    data.get("bootId") == null ? null : String.valueOf(data.get("bootId")),
                    data.get("eventSeq") instanceof Number es ? es.longValue() : null,
                    data.get("lastCommandSeq") instanceof Number ls ? ls.longValue() : null);
            log.info("状态查询成功 cabinetNo={} state={} lastCommandSeq={} cells={}",
                    cabinetNo, result.state(), result.lastCommandSeq(), cells.size());
            return result;
        } catch (RestClientException e) {
            log.warn("状态查询失败(设备无响应) cabinetNo={} cause={}", cabinetNo, e.getMessage());
            return null;
        }
    }

    /** 准备一次充电策略下发（S4.3）：seq + PENDING 流水（策略载荷不占 cellNo） */
    public CommandLogEntity preparePolicy(String cabinetNo, String traceId) {
        requireDispatchable(cabinetNo);
        long seq = commandLogService.nextSeq(cabinetNo);
        return commandLogService.recordPending(cabinetNo, CommandAction.SET_CHARGE_POLICY, seq, traceId);
    }

    /**
     * 下发充电策略（S4.3）：canonical 含策略版本（载荷不可篡改）；柜侧版本单调。
     * 成功（含同版本幂等）销账 ARRIVED；失败记 SEND_FAILED 并抛业务异常。
     */
    public void dispatchPolicy(CommandLogEntity cmdLog, String cabinetNo, long version,
                               Map<String, Object> policy) {
        CabinetEntity cabinet = requireDispatchable(cabinetNo);
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", CommandAction.SET_CHARGE_POLICY.name());
        payload.put("cabinetNo", cabinetNo);
        payload.put("commandSeq", cmdLog.getCommandSeq());
        payload.put("policy", policy);
        HttpHeaders headers = new HttpHeaders();
        headers.set(DEVICE_HEADER, cabinetNo);
        headers.set(SIGN_HEADER, DeviceSignature.sign(cabinet.getSecret(),
                DeviceSignature.canonicalPolicy(cabinetNo, version, cmdLog.getCommandSeq())));
        headers.set(TraceIdFilter.TRACE_ID_HEADER, cmdLog.getTraceId());
        try {
            Map<String, Object> resp = downlinkGuard.call(
                    () -> postJson(properties.getSimBaseUrl() + CMD_PATH, payload, headers),
                    () -> Map.of("code", -1, "msg", "设备通道熔断/过载（快速失败）"));
            Object code = resp == null ? null : resp.get("code");
            if (resp == null || !Integer.valueOf(0).equals(code)) {
                commandLogService.markSendFailed(cmdLog.getId());
                Object msg = resp == null ? "空响应" : resp.get("msg");
                throw new RRException("柜拒绝策略: " + msg);
            }
            commandLogService.markArrivedBySeq(cabinetNo, cmdLog.getCommandSeq(), CommandAction.SET_CHARGE_POLICY);
            log.info("充电策略已下发 cabinetNo={} version={} seq={}", cabinetNo, version, cmdLog.getCommandSeq());
        } catch (HttpStatusCodeException e) {
            commandLogService.markSendFailed(cmdLog.getId());
            throw new RRException("柜拒绝策略(HTTP " + e.getStatusCode().value() + ")");
        } catch (RestClientException e) {
            commandLogService.markSendFailed(cmdLog.getId());
            throw new RRException("柜无响应(连接失败): " + cabinetNo + " cause=" + e.getMessage());
        }
    }

    /** 联调便捷：准备 + 立即下发（DevOpsController 使用） */
    public CommandLogEntity openCell(String cabinetNo, int cellNo) {
        String traceId = TraceIdFilter.currentOrGenerate();
        CommandLogEntity cmdLog = prepareOpen(cabinetNo, cellNo, traceId);
        try {
            dispatchPrepared(cmdLog, cabinetNo, cellNo);
            return cmdLog;
        } catch (RRException e) {
            throw e;
        }
    }

    /** 统一 HTTP POST（json 响应体）；异常原样抛出交由护栏计数与调用方分级处理 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String url, Map<String, Object> payload, HttpHeaders headers) {
        return restTemplate.postForObject(url, new HttpEntity<>(payload, headers), Map.class);
    }

    private CabinetEntity requireDispatchable(String cabinetNo) {
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo));
        if (cabinet == null) {
            throw new RRException("柜未登记: " + cabinetNo);
        }
        if (cabinet.getSecret() == null || cabinet.getSecret().isEmpty()) {
            throw new RRException("柜密钥缺失，拒绝下发: " + cabinetNo);
        }
        return cabinet;
    }
}
