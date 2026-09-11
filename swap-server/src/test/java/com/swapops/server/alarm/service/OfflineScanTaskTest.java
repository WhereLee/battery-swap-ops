package com.swapops.server.alarm.service;

import com.swapops.contract.CabinetStatus;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.config.AlarmProperties;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.service.MonitorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 离线扫描单测（S3.6）：单柜/批量合并/恢复自动关/人工态跳过/租约互斥。
 */
@DisplayName("离线扫描（离线告警+恢复）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OfflineScanTaskTest {

    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private MonitorService monitorService;
    @Mock
    private AlarmService alarmService;
    @Mock
    private JobLockService jobLockService;
    @Mock
    private TaskWatchdog watchdog;

    private OfflineScanTask task;

    @BeforeEach
    void setUp() {
        task = new OfflineScanTask(cabinetDao, monitorService, alarmService, new AlarmProperties(),
                jobLockService, watchdog);
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return true;
        });
    }

    private CabinetEntity cabinet(String no, Integer status) {
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setCabinetNo(no);
        cabinet.setStatus(status);
        return cabinet;
    }

    @Test
    @DisplayName("批量离线（≥阈值）：合并一条 BATCH_OFFLINE，不发单柜告警")
    void 批量离线合并() {
        List<CabinetEntity> cabinets = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            cabinets.add(cabinet("SWAP-C-00" + i, CabinetStatus.ONLINE.getCode()));
        }
        when(cabinetDao.selectList(any())).thenReturn(cabinets);
        when(monitorService.isOnline(anyString())).thenReturn(false);

        task.scan();

        verify(alarmService).raise(eq(AlarmService.DEVICE_CABINET), eq("BATCH"), eq(AlarmType.BATCH_OFFLINE),
                anyString());
        verify(alarmService, never()).raise(anyString(), eq("SWAP-C-001"), eq(AlarmType.OFFLINE), anyString());
    }

    @Test
    @DisplayName("少量离线（<阈值）：单柜 OFFLINE + 关闭批量告警")
    void 少量离线单柜告警() {
        when(cabinetDao.selectList(any())).thenReturn(List.of(
                cabinet("SWAP-C-001", CabinetStatus.ONLINE.getCode()),
                cabinet("SWAP-C-002", CabinetStatus.FULL.getCode())));
        when(monitorService.isOnline(anyString())).thenReturn(false);

        task.scan();

        verify(alarmService, times(2)).raise(eq(AlarmService.DEVICE_CABINET), anyString(),
                eq(AlarmType.OFFLINE), anyString());
        verify(alarmService).markRecovered(AlarmService.DEVICE_CABINET, "BATCH", AlarmType.BATCH_OFFLINE);
    }

    @Test
    @DisplayName("心跳恢复：在线柜自动关离线告警；人工态跳过不告警")
    void 恢复与人工态跳过() {
        when(cabinetDao.selectList(any())).thenReturn(List.of(
                cabinet("SWAP-C-001", CabinetStatus.ONLINE.getCode()),
                cabinet("SWAP-C-002", CabinetStatus.MAINTENANCE.getCode())));
        when(monitorService.isOnline("SWAP-C-001")).thenReturn(true);

        task.scan();

        verify(alarmService).markOnlineRecovered("SWAP-C-001");
        verify(alarmService).markRecovered(AlarmService.DEVICE_CABINET, "BATCH", AlarmType.BATCH_OFFLINE);
        verify(alarmService, never()).raise(anyString(), eq("SWAP-C-002"), any(), anyString());
    }

    @Test
    @DisplayName("租约锁未抢到：跳过本轮")
    void 未抢到锁跳过() {
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenReturn(false);

        task.scan();

        verifyNoInteractions(cabinetDao, alarmService);
    }
}
