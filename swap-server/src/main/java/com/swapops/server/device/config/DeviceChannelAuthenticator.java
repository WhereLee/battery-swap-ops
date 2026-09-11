package com.swapops.server.device.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.contract.DeviceSignature;
import com.swapops.contract.EventType;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.form.DeviceEventForm;
import com.swapops.server.device.form.DeviceHeartbeatForm;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 设备通道鉴权（per-柜 HMAC）：按柜号取密钥、常量时间比对；
 * 失败 401、协议垃圾 400（不吞成 200）。
 */
@Component
public class DeviceChannelAuthenticator {

    private final CabinetDao cabinetDao;

    public DeviceChannelAuthenticator(CabinetDao cabinetDao) {
        this.cabinetDao = cabinetDao;
    }

    /** 事件验签（协议 v1 §4）；返回柜档案供后续使用 */
    public CabinetEntity authenticateEvent(DeviceEventForm form, String signature) {
        CabinetEntity cabinet = requireCabinet(form.getCabinetNo());
        EventType type;
        try {
            type = EventType.fromWire(form.getEventType());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知事件类型: " + form.getEventType());
        }
        String canonical = DeviceSignature.canonicalEvent(form.getCabinetNo(), type, form.getCellNo(),
                form.getBatteryNo(), form.getBootId(), form.getEventSeq());
        if (!DeviceSignature.verify(cabinet.getSecret(), canonical, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "柜签名无效: " + form.getCabinetNo());
        }
        return cabinet;
    }

    /** 心跳验签（canonical = cabinetNo|status） */
    public CabinetEntity authenticateHeartbeat(DeviceHeartbeatForm form, String signature) {
        CabinetEntity cabinet = requireCabinet(form.getCabinetNo());
        String canonical = DeviceSignature.canonicalHeartbeat(form.getCabinetNo(), form.getStatus());
        if (!DeviceSignature.verify(cabinet.getSecret(), canonical, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "柜签名无效: " + form.getCabinetNo());
        }
        return cabinet;
    }

    private CabinetEntity requireCabinet(String cabinetNo) {
        if (cabinetNo == null || cabinetNo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺柜编号");
        }
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo));
        if (cabinet == null || cabinet.getSecret() == null || cabinet.getSecret().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "柜未登记或未配置密钥: " + cabinetNo);
        }
        return cabinet;
    }
}
