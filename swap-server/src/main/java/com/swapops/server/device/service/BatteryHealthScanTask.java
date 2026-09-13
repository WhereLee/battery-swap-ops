package com.swapops.server.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.contract.BatteryStatus;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.alarm.service.TaskWatchdog;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.config.BatteryHealthProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.entity.BatteryEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 电池健康扫描（S4.1，租约锁互斥）：
 * SOH 低于阈值 → BATTERY_HEALTH_LOW 告警（归工单处置）；SOH 回升 → 自动关（去重窗口由 AlarmService 管）。
 * 阈值/间隔可配（联调缩短）；退役电池不参与。
 */
@Slf4j
@Component
public class BatteryHealthScanTask {

    private static final int BATCH = 200;

    private final BatteryDao batteryDao;
    private final AlarmService alarmService;
    private final BatteryHealthProperties properties;
    private final JobLockService jobLockService;
    private final TaskWatchdog watchdog;

    public BatteryHealthScanTask(BatteryDao batteryDao, AlarmService alarmService,
                                 BatteryHealthProperties properties, JobLockService jobLockService,
                                 TaskWatchdog watchdog) {
        this.batteryDao = batteryDao;
        this.alarmService = alarmService;
        this.properties = properties;
        this.jobLockService = jobLockService;
        this.watchdog = watchdog;
    }

    @Scheduled(fixedDelayString = "${swap.battery.scan-interval-ms:3600000}")
    public void scan() {
        if (!jobLockService.runWithLock("battery-health-scan", this::doScan)) {
            log.debug("电池健康扫描：另一实例执行中，跳过本轮");
        }
    }

    private void doScan() {
        List<BatteryEntity> low = batteryDao.selectList(new LambdaQueryWrapper<BatteryEntity>()
                .ne(BatteryEntity::getStatus, BatteryStatus.RETIRED.getCode())
                .lt(BatteryEntity::getSoh, properties.getSohWarnThreshold())
                .last("LIMIT " + BATCH));
        for (BatteryEntity battery : low) {
            alarmService.raise(AlarmService.DEVICE_BATTERY, battery.getBatteryNo(),
                    AlarmType.BATTERY_HEALTH_LOW,
                    "SOH=" + battery.getSoh() + " 低于阈值 " + properties.getSohWarnThreshold());
        }
        List<BatteryEntity> healthy = batteryDao.selectList(new LambdaQueryWrapper<BatteryEntity>()
                .ne(BatteryEntity::getStatus, BatteryStatus.RETIRED.getCode())
                .ge(BatteryEntity::getSoh, properties.getSohWarnThreshold())
                .last("LIMIT " + BATCH));
        for (BatteryEntity battery : healthy) {
            alarmService.markRecovered(AlarmService.DEVICE_BATTERY, battery.getBatteryNo(),
                    AlarmType.BATTERY_HEALTH_LOW);
        }
        watchdog.beat("battery-health-scan");
    }
}
