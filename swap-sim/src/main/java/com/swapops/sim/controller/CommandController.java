package com.swapops.sim.controller;

import com.swapops.contract.DeviceSignature;
import com.swapops.sim.config.SimProperties;
import com.swapops.sim.config.TraceIds;
import com.swapops.sim.model.CabinetSim;
import com.swapops.sim.model.ChargePolicy;
import com.swapops.sim.registry.SimRegistry;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 指令接收口（平台 → 柜）：契约翻译 + 下行验签；业务裁决在 CabinetSim。
 * 回执 code=0 受理（含幂等忽略）/ code=1 拒绝；协议垃圾 400；验签失败 401。
 */
@RestController
public class CommandController {

    private static final String SIGN_HEADER = "X-Device-Sign";

    private final SimRegistry registry;
    private final SimProperties properties;

    public CommandController(SimRegistry registry, SimProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @PostMapping("/cmd")
    public Map<String, Object> cmd(@RequestHeader(value = SIGN_HEADER, required = false) String signature,
                                   @RequestHeader(value = TraceIds.HEADER, required = false) String incomingTraceId,
                                   @RequestBody Map<String, Object> req) {
        String traceId = TraceIds.orGenerate(incomingTraceId);
        MDC.put(TraceIds.MDC_KEY, traceId);
        try {
            return doCmd(signature, req, traceId);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }

    @PostMapping("/cmd/query")
    public Map<String, Object> query(@RequestHeader(value = SIGN_HEADER, required = false) String signature,
                                     @RequestHeader(value = TraceIds.HEADER, required = false) String incomingTraceId,
                                     @RequestBody Map<String, Object> req) {
        String traceId = TraceIds.orGenerate(incomingTraceId);
        MDC.put(TraceIds.MDC_KEY, traceId);
        try {
            String cabinetNo = asString(req.get("cabinetNo"));
            if (cabinetNo == null) {
                return Map.of("code", 1, "msg", "缺 cabinetNo");
            }
            authenticateQuery(cabinetNo, signature);
            CabinetSim cabinet = registry.get(cabinetNo);
            if (cabinet == null) {
                return Map.of("code", 1, "msg", "柜不存在: " + cabinetNo);
            }
            Map<String, Object> data = new HashMap<>(cabinet.snapshot());
            return Map.of("code", 0, "data", data);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }

    private Map<String, Object> doCmd(String signature, Map<String, Object> req, String traceId) {
        String cabinetNo = asString(req.get("cabinetNo"));
        String action = asString(req.get("action"));
        Object seqObj = req.get("commandSeq");
        if (cabinetNo == null || seqObj == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺 cabinetNo/commandSeq");
        }
        long seq;
        try {
            seq = Long.parseLong(String.valueOf(seqObj));
        } catch (NumberFormatException e) {
            return Map.of("code", 1, "msg", "commandSeq 非法: " + seqObj);
        }
        if ("SET_CHARGE_POLICY".equals(action)) {
            return doPolicy(signature, req, cabinetNo, seq);
        }
        Integer cellNo = asInt(req.get("cellNo"));
        if (cellNo == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺 cellNo");
        }
        authenticate(cabinetNo, cellNo, seq, signature);

        CabinetSim cabinet = registry.get(cabinetNo);
        if (cabinet == null) {
            return Map.of("code", 1, "msg", "柜不存在: " + cabinetNo);
        }
        try {
            boolean accepted = cabinet.openCell(cellNo, seq, traceId);
            return Map.of("code", 0, "msg", accepted ? "指令已受理" : "重复指令已幂等忽略(seq=" + seq + ")");
        } catch (IllegalStateException | IllegalArgumentException e) {
            return Map.of("code", 1, "msg", e.getMessage());
        }
    }

    /** 充电策略下发（S4.3）：canonical 含版本，载荷不可篡改；版本单调（旧拒/同幂等） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> doPolicy(String signature, Map<String, Object> req, String cabinetNo, long seq) {
        Object policyObj = req.get("policy");
        if (!(policyObj instanceof Map<?, ?> policyMap)) {
            return Map.of("code", 1, "msg", "缺策略体 policy");
        }
        long version;
        int priority;
        List<ChargePolicy.Window> windows = new java.util.ArrayList<>();
        try {
            version = Long.parseLong(String.valueOf(policyMap.get("version")));
            Object priorityObj = policyMap.get("priority");
            priority = priorityObj == null ? 2 : Integer.parseInt(String.valueOf(priorityObj));
            Object windowsObj = policyMap.get("windows");
            if (!(windowsObj instanceof List<?> windowList) || windowList.isEmpty()) {
                return Map.of("code", 1, "msg", "策略窗口为空");
            }
            for (Object item : windowList) {
                if (!(item instanceof Map<?, ?> windowMap)) {
                    return Map.of("code", 1, "msg", "策略窗口格式非法");
                }
                windows.add(new ChargePolicy.Window(
                        Integer.parseInt(String.valueOf(windowMap.get("startHour"))),
                        Integer.parseInt(String.valueOf(windowMap.get("endHour"))),
                        Integer.parseInt(String.valueOf(windowMap.get("powerLimitW")))));
            }
        } catch (NumberFormatException e) {
            return Map.of("code", 1, "msg", "策略字段非法: " + e.getMessage());
        }
        String secret = properties.secretOf(cabinetNo);
        if (secret == null || secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "柜未配置密钥: " + cabinetNo);
        }
        if (!DeviceSignature.verify(secret, DeviceSignature.canonicalPolicy(cabinetNo, version, seq), signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "平台签名无效: " + cabinetNo);
        }
        CabinetSim cabinet = registry.get(cabinetNo);
        if (cabinet == null) {
            return Map.of("code", 1, "msg", "柜不存在: " + cabinetNo);
        }
        try {
            boolean applied = cabinet.applyPolicy(new ChargePolicy(version, priority, windows));
            return Map.of("code", 0, "msg", applied ? "策略已应用" : "同版本策略幂等忽略",
                    "data", Map.of("appliedVersion", version));
        } catch (IllegalStateException e) {
            return Map.of("code", 1, "msg", e.getMessage());
        }
    }

    private void authenticate(String cabinetNo, Integer cellNo, Long seq, String signature) {
        String secret = properties.secretOf(cabinetNo);
        if (secret == null || secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "柜未配置密钥: " + cabinetNo);
        }
        String canonical = DeviceSignature.canonicalCommand(cabinetNo, cellNo, seq);
        if (!DeviceSignature.verify(secret, canonical, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "平台签名无效: " + cabinetNo);
        }
    }

    /** 查询验签（canonical = cabinetNo|QUERY|0，与 S0.3 §2.4 一致） */
    private void authenticateQuery(String cabinetNo, String signature) {
        String secret = properties.secretOf(cabinetNo);
        if (secret == null || secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "柜未配置密钥: " + cabinetNo);
        }
        if (!DeviceSignature.verify(secret, DeviceSignature.canonicalQuery(cabinetNo), signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "平台签名无效: " + cabinetNo);
        }
    }

    private String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    private Integer asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? null : Integer.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
