package com.swapops.sim.model;

import java.util.List;

/**
 * 充电策略（S4.3，sim 侧只消费功率上限；费率由平台侧计算，柜端不感知）：
 * 版本单调；窗口未覆盖的小时=0W（保守不充，缺省策略由 sim 配置兜底）。
 */
public record ChargePolicy(long version, int priority, List<Window> windows) {

    public record Window(int startHour, int endHour, int powerLimitW) {
    }

    public int powerLimitAt(int hour) {
        for (Window window : windows) {
            if (hour >= window.startHour() && hour < window.endHour()) {
                return window.powerLimitW();
            }
        }
        return 0;
    }
}
