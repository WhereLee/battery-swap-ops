package com.swapops.server.device.controller;

import com.swapops.server.common.Result;
import com.swapops.server.device.config.DeviceChannelAuthenticator;
import com.swapops.server.device.form.DeviceEventForm;
import com.swapops.server.device.service.DeviceEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件入口（柜 → 平台）：HMAC 验签 → 序守卫 → 事件语义 → 指令销账。
 * 设备通道错误语义：401 验签失败 / 400 协议垃圾（不吞成 200）。
 */
@Slf4j
@RestController
@RequestMapping("device/event")
public class DeviceEventController {

    private static final String DEVICE_HEADER = "X-Device-No";
    private static final String SIGN_HEADER = "X-Device-Sign";

    private final DeviceChannelAuthenticator authenticator;
    private final DeviceEventService deviceEventService;

    public DeviceEventController(DeviceChannelAuthenticator authenticator, DeviceEventService deviceEventService) {
        this.authenticator = authenticator;
        this.deviceEventService = deviceEventService;
    }

    @PostMapping
    public Result<Void> report(@RequestHeader(value = DEVICE_HEADER, required = false) String deviceNoHeader,
                               @RequestHeader(value = SIGN_HEADER, required = false) String signature,
                               @RequestBody DeviceEventForm form) {
        // 报文级留痕（字段化；traceId 由 TraceIdFilter 置 MDC）
        log.info("[事件入口] cabinetNo={} eventType={} cellNo={} batteryNo={} commandSeq={} bootId={} eventSeq={}",
                form.getCabinetNo(), form.getEventType(), form.getCellNo(), form.getBatteryNo(),
                form.getCommandSeq(), form.getBootId(), form.getEventSeq());
        authenticator.authenticateEvent(form, signature);
        deviceEventService.handle(form);
        return Result.ok();
    }
}
