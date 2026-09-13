package com.swapops.server.charge.form;

import lombok.Data;

import java.util.List;

/**
 * 充电策略表单（S4.3）：窗口须连续覆盖 0~24h（缺口=0W 不符合生产直觉，显式拒绝）。
 */
@Data
public class ChargePolicyForm {

    private String cabinetNo;

    /** 1 高 / 2 中 / 3 低 */
    private Integer priority = 2;

    private List<Window> windows;

    @Data
    public static class Window {
        private Integer startHour;
        private Integer endHour;
        /** 该时段总充电功率上限（W） */
        private Integer powerLimitW;
        /** 该时段电价（分/kWh；柜端不感知，平台计成本对比用） */
        private Integer feeFenPerKwh;
    }
}
