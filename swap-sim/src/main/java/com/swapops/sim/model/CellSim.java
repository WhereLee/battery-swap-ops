package com.swapops.sim.model;

import lombok.Getter;

/**
 * 模拟仓：物理状态（有无电池）+ 当前电池信息（简化：编号/SOC）。
 */
@Getter
public class CellSim {

    private final int cellNo;

    private boolean hasBattery;

    private String batteryNo;

    private int soc;

    public CellSim(int cellNo, boolean hasBattery, String batteryNo, int soc) {
        this.cellNo = cellNo;
        this.hasBattery = hasBattery;
        this.batteryNo = batteryNo;
        this.soc = soc;
    }

    public void putBattery(String batteryNo, int soc) {
        this.hasBattery = true;
        this.batteryNo = batteryNo;
        this.soc = soc;
    }

    public String takeBattery() {
        String taken = this.batteryNo;
        this.hasBattery = false;
        this.batteryNo = null;
        this.soc = 0;
        return taken;
    }
}
