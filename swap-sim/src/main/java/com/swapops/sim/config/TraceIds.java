package com.swapops.sim.config;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 模拟器链路号：入站沿用、无则生成；跨线程显式传递（MDC 不自动继承）。
 */
public final class TraceIds {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    /** MQ message property 键（契约 §7：traceId 随 property 贯穿；与平台消费侧同值） */
    public static final String MQ_PROPERTY = "traceId";

    private TraceIds() {
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static String orGenerate(String incoming) {
        return (incoming == null || incoming.isBlank()) ? generate() : incoming;
    }

    public static String currentOrGenerate() {
        return orGenerate(MDC.get(MDC_KEY));
    }
}
