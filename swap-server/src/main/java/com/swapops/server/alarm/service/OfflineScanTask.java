package com.swapops.server.alarm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.contract.CabinetStatus;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.config.AlarmProperties;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.service.MonitorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 离线扫描（S3.6，租约锁互斥）：心跳 TTL 缺失且非人工态 → OFFLINE；
 * 离线柜数 ≥ 阈值时合并一条 BATCH_OFFLINE（不发单柜告警）；心跳恢复自动关。
 * 人工态（MAINTENANCE/DISABLED）视为计划离线，不告警。
 */
@Slf4j
@Component
public class OfflineScanTask {

    private static final String BATCH_DEVICE_NO = "BATCH";

    private final CabinetDao cabinetDao;
    private final MonitorService monitorService;
    private final AlarmService alarmService;
    private final AlarmProperties properties;
    private final JobLockService jobLockService;
    private final TaskWatchdog watchdog;

    public OfflineScanTask(CabinetDao cabinetDao, MonitorService monitorService, AlarmService alarmService,
                           AlarmProperties properties, JobLockService jobLockService, TaskWatchdog watchdog) {
        this.cabinetDao = cabinetDao;
        this.monitorService = monitorService;
        this.alarmService = alarmService;
        this.properties = properties;
        this.jobLockService = jobLockService;
        this.watchdog = watchdog;
    }

    @Scheduled(fixedDelayString = "${swap.alarm.offline-scan-interval-ms:30000}")
    public void scan() {
        if (!jobLockService.runWithLock("offline-scan", this::doScan)) {
            log.debug("离线扫描：另一实例执行中，跳过本轮");
        }
    }

    private void doScan() {
        List<CabinetEntity> cabinets = cabinetDao.selectList(new LambdaQueryWrapper<CabinetEntity>());
        List<String> offline = new ArrayList<>();
        for (CabinetEntity cabinet : cabinets) {
            if (isAdminHeld(cabinet.getStatus())) {
                continue;
            }
            if (monitorService.isOnline(cabinet.getCabinetNo())) {
                alarmService.markOnlineRecovered(cabinet.getCabinetNo());
            } else {
                offline.add(cabinet.getCabinetNo());
            }
        }
        if (offline.isEmpty()) {
            alarmService.markRecovered(AlarmService.DEVICE_CABINET, BATCH_DEVICE_NO, AlarmType.BATCH_OFFLINE);
        } else if (offline.size() >= properties.getBatchOfflineThreshold()) {
            String sample = offline.stream().limit(10).collect(Collectors.joining(","));
            alarmService.raise(AlarmService.DEVICE_CABINET, BATCH_DEVICE_NO, AlarmType.BATCH_OFFLINE,
                    "批量离线 " + offline.size() + " 柜: " + sample);
        } else {
            for (String cabinetNo : offline) {
                alarmService.raise(AlarmService.DEVICE_CABINET, cabinetNo, AlarmType.OFFLINE,
                        "心跳超时离线（TTL=" + properties.getOfflineScanIntervalMs() + "ms 扫描）");
            }
            // 离线数回落（未达批量阈值）：关闭未处理的批量告警
            alarmService.markRecovered(AlarmService.DEVICE_CABINET, BATCH_DEVICE_NO, AlarmType.BATCH_OFFLINE);
        }
        watchdog.beat("offline-scan");
    }

    private boolean isAdminHeld(Integer status) {
        return status != null && (status == CabinetStatus.MAINTENANCE.getCode()
                || status == CabinetStatus.DISABLED.getCode());
    }
}
