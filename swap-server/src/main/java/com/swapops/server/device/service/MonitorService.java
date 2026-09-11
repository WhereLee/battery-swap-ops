package com.swapops.server.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.contract.CabinetStatus;
import com.swapops.server.common.RRException;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.config.SwapRedisKeys;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.form.DeviceHeartbeatForm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 设备监控：心跳 → 在线 TTL 续命 + 自述状态落库（判活不依赖任何消息中间件）。
 */
@Slf4j
@Service
public class MonitorService {

    private final CabinetDao cabinetDao;
    private final StringRedisTemplate stringRedisTemplate;
    private final DeviceChannelProperties properties;

    public MonitorService(CabinetDao cabinetDao, StringRedisTemplate stringRedisTemplate,
                          DeviceChannelProperties properties) {
        this.cabinetDao = cabinetDao;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    public void heartbeat(DeviceHeartbeatForm form) {
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, form.getCabinetNo()));
        if (cabinet == null) {
            throw new RRException("未登记的柜心跳: " + form.getCabinetNo());
        }
        long now = System.currentTimeMillis();
        // 覆盖式 SET + TTL：停止心跳后自然过期 = 离线
        stringRedisTemplate.opsForValue().set(SwapRedisKeys.ONLINE_PREFIX + form.getCabinetNo(),
                String.valueOf(now), properties.getHeartbeatTimeoutSeconds(), TimeUnit.SECONDS);
        CabinetEntity update = new CabinetEntity();
        update.setId(cabinet.getId());
        update.setLastHeartbeatTime(now);
        update.setUpdateTime(now);
        if (form.getStatus() != null) {
            update.setStatus(CabinetStatus.fromCode(form.getStatus()).getCode());
        }
        cabinetDao.updateById(update);
        log.debug("心跳已刷新 cabinetNo={} status={}", form.getCabinetNo(), form.getStatus());
    }

    public boolean isOnline(String cabinetNo) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(SwapRedisKeys.ONLINE_PREFIX + cabinetNo));
    }
}
