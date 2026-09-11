package com.swapops.server.device.controller;

import com.swapops.contract.CabinetStatus;
import com.swapops.server.common.Result;
import com.swapops.server.device.config.DeviceChannelAuthenticator;
import com.swapops.server.device.form.DeviceHeartbeatForm;
import com.swapops.server.device.service.MonitorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 心跳入口（柜 → 平台）：HMAC 验签 → 在线 TTL 续命 + 自述落库。
 */
@Slf4j
@RestController
@RequestMapping("device/heartbeat")
public class DeviceHeartbeatController {

    private static final String DEVICE_HEADER = "X-Device-No";
    private static final String SIGN_HEADER = "X-Device-Sign";

    private final DeviceChannelAuthenticator authenticator;
    private final MonitorService monitorService;

    public DeviceHeartbeatController(DeviceChannelAuthenticator authenticator, MonitorService monitorService) {
        this.authenticator = authenticator;
        this.monitorService = monitorService;
    }

    @PostMapping
    public Result<Void> heartbeat(@RequestHeader(value = DEVICE_HEADER, required = false) String deviceNoHeader,
                                  @RequestHeader(value = SIGN_HEADER, required = false) String signature,
                                  @RequestBody DeviceHeartbeatForm form) {
        log.debug("[心跳入口] cabinetNo={} status={}", form.getCabinetNo(), form.getStatus());
        if (form.getStatus() != null) {
            try {
                CabinetStatus.fromCode(form.getStatus());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "非法柜状态码: " + form.getStatus() + "（协议 v1：1 在线 2 满载 3 故障 4 维护）");
            }
        }
        authenticator.authenticateHeartbeat(form, signature);
        monitorService.heartbeat(form);
        return Result.ok();
    }
}
