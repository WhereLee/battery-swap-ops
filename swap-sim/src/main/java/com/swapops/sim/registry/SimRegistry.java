package com.swapops.sim.registry;

import com.swapops.sim.config.SimProperties;
import com.swapops.sim.model.CabinetSim;
import com.swapops.sim.reporter.EventReporter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 本进程"安装"的柜注册表：进程级 bootId（重启代际）+ 逐柜电池编号偏移。
 * 密钥缺失即启动失败（fail-fast：50 台全静默不上报是最难发现的事故形态）。
 */
@Slf4j
@Component
public class SimRegistry {

    private final SimProperties properties;
    private final EventReporter reporter;
    private final Map<String, CabinetSim> cabinets = new LinkedHashMap<>();
    private final String bootId = UUID.randomUUID().toString().replace("-", "");

    public SimRegistry(SimProperties properties, EventReporter reporter) {
        this.properties = properties;
        this.reporter = reporter;
    }

    @PostConstruct
    public void init() {
        int index = 0;
        for (SimProperties.CabinetCfg cfg : properties.getCabinets()) {
            if (cfg.getSecret() == null || cfg.getSecret().isBlank()) {
                throw new IllegalStateException("柜密钥未配置（经 SWAP_DEV_SECRET 环境变量注入）: " + cfg.getCabinetNo());
            }
            index++;
            cabinets.put(cfg.getCabinetNo(), new CabinetSim(cfg.getCabinetNo(), bootId, properties,
                    reporter, (index - 1) * properties.getCellsPerCabinet()));
        }
        log.info("设备注册完成：{} 台柜 × {} 仓（满电 {}），bootId={}",
                cabinets.size(), properties.getCellsPerCabinet(), properties.getFullCells(), bootId);
    }

    public CabinetSim get(String cabinetNo) {
        return cabinets.get(cabinetNo);
    }

    public Collection<CabinetSim> all() {
        return Collections.unmodifiableCollection(cabinets.values());
    }

    public int size() {
        return cabinets.size();
    }
}
