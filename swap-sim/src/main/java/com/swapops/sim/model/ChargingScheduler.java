package com.swapops.sim.model;

import com.swapops.sim.config.SimProperties;
import com.swapops.sim.registry.SimRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 充电仿真调度（S4.3）：按 tick 间隔驱动所有柜推进 SOC（策略功率约束在 CabinetSim.chargeTick 内）。
 */
@Slf4j
@Component
public class ChargingScheduler {

    private final SimRegistry registry;
    private final SimProperties properties;

    public ChargingScheduler(SimRegistry registry, SimProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @Scheduled(fixedRateString = "${swap.sim.charge-tick-millis:1000}")
    public void tick() {
        long now = System.currentTimeMillis();
        for (CabinetSim cabinet : registry.all()) {
            try {
                cabinet.chargeTick(now, properties.getChargeTickMillis());
            } catch (Exception e) {
                log.warn("[{}] 充电仿真 tick 异常（跳过）: {}", cabinet.getCabinetNo(), e.getMessage());
            }
        }
    }
}
