package com.swapops.sim.reporter;

import com.swapops.contract.EventType;

/**
 * 待上报事件（跨线程传递 traceId，不依赖 MDC 继承）。
 */
public record DeviceEventMessage(
        String cabinetNo,
        EventType eventType,
        Integer cellNo,
        String batteryNo,
        Integer soc,
        Long commandSeq,
        String bootId,
        long eventSeq,
        String traceId) {
}
