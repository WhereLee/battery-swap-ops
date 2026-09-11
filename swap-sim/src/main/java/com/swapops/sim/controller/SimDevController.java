package com.swapops.sim.controller;

import com.swapops.sim.config.TraceIds;
import com.swapops.sim.model.CabinetSim;
import com.swapops.sim.registry.SimRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模拟器联调端点（仅 swap.sim.dev-enabled=true）：模拟取电/还电/故障注入。
 * 供剧本 2/3 使用；真实柜中这些由物理动作产生。
 */
@RestController
@RequestMapping("sim")
@ConditionalOnProperty(prefix = "swap.sim", name = "dev-enabled", havingValue = "true")
public class SimDevController {

    private final SimRegistry registry;

    public SimDevController(SimRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/battery/out")
    public Map<String, Object> batteryOut(@RequestParam String cabinetNo, @RequestParam int cellNo) {
        CabinetSim cabinet = require(cabinetNo);
        cabinet.devTake(cellNo, TraceIds.generate());
        return ok("取电已模拟（事件已上报）");
    }

    @PostMapping("/battery/in")
    public Map<String, Object> batteryIn(@RequestParam String cabinetNo, @RequestParam int cellNo,
                                         @RequestParam String batteryNo, @RequestParam(defaultValue = "20") int soc) {
        CabinetSim cabinet = require(cabinetNo);
        cabinet.devPut(cellNo, batteryNo, soc, TraceIds.generate());
        return ok("还电已模拟（事件已上报）");
    }

    @PostMapping("/fault")
    public Map<String, Object> fault(@RequestParam String cabinetNo, @RequestParam boolean doorStuck) {
        CabinetSim cabinet = require(cabinetNo);
        cabinet.setDoorStuck(doorStuck);
        return ok("故障注入已更新 doorStuck=" + doorStuck);
    }

    private CabinetSim require(String cabinetNo) {
        CabinetSim cabinet = registry.get(cabinetNo);
        if (cabinet == null) {
            throw new IllegalArgumentException("柜不存在: " + cabinetNo);
        }
        return cabinet;
    }

    private Map<String, Object> ok(String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("msg", msg);
        return result;
    }
}
