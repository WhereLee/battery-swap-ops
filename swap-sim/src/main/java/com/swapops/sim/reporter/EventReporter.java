package com.swapops.sim.reporter;

/**
 * 事件上报接口（HTTP 实现；S3 增 MQ 通道时在此扩展路由）。
 */
public interface EventReporter {

    void report(DeviceEventMessage message);
}
