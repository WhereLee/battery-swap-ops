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

    /** 进程级 bootId；dev 重置时轮换（等价一次"设备重启"，配合平台代际守卫） */
    private volatile String bootId = newBootId();

    public SimRegistry(SimProperties properties, EventReporter reporter) {
        this.properties = properties;
        this.reporter = reporter;
    }

    private static final String SECRET_PATTERN = "^[0-9a-f]{32}$";

    @PostConstruct
    public void init() {
        seed();
    }

    /**
     * 联调重置（P0-4）：轮换 bootId + 重建全部柜到种子态（旧实例线程池关闭）。
     * 与平台 dev-reset 成对使用，保证演示可重复；生产不暴露（端点仅 dev-enabled）。
     */
    public synchronized void reset() {
        for (CabinetSim cabinet : cabinets.values()) {
            cabinet.shutdown();
        }
        cabinets.clear();
        bootId = newBootId();
        seed();
        log.info("设备重置完成：{} 台柜 × {} 仓（满电 {}），新 bootId={}",
                cabinets.size(), properties.getCellsPerCabinet(), properties.getFullCells(), bootId);
    }

    private void seed() {
        int index = 0;
        for (SimProperties.CabinetCfg cfg : properties.getCabinets()) {
            if (cfg.getSecret() == null || !cfg.getSecret().matches(SECRET_PATTERN)) {
                throw new IllegalStateException(
                        "柜密钥未配置或格式非法（须 32hex，经 SWAP_DEV_SECRET 注入）: " + cfg.getCabinetNo());
            }
            index++;
            cabinets.put(cfg.getCabinetNo(), new CabinetSim(cfg.getCabinetNo(), bootId, properties,
                    reporter, (index - 1) * properties.getCellsPerCabinet()));
        }
        log.info("设备注册完成：{} 台柜 × {} 仓（满电 {}），bootId={}",
                cabinets.size(), properties.getCellsPerCabinet(), properties.getFullCells(), bootId);
    }

    private static String newBootId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public String getBootId() {
        return bootId;
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
