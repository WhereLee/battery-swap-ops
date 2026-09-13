package com.swapops.server.device.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.alarm.service.TaskWatchdog;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.config.BatteryHealthProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.entity.BatteryEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 电池健康扫描单测（S4.1）：低 SOH 告警、回升自动关、租约互斥。
 */
@DisplayName("电池健康扫描")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatteryHealthScanTaskTest {

    @Mock
    private BatteryDao batteryDao;
    @Mock
    private AlarmService alarmService;
    @Mock
    private JobLockService jobLockService;
    @Mock
    private TaskWatchdog watchdog;

    private BatteryHealthScanTask task;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BatteryEntity.class);
    }

    @BeforeEach
    void setUp() {
        task = new BatteryHealthScanTask(batteryDao, alarmService, new BatteryHealthProperties(),
                jobLockService, watchdog);
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return true;
        });
    }

    private BatteryEntity battery(String no, int soh) {
        BatteryEntity battery = new BatteryEntity();
        battery.setBatteryNo(no);
        battery.setSoh(soh);
        battery.setStatus(2);
        return battery;
    }

    @Test
    @DisplayName("低 SOH：告警；健康电池：自动关")
    void 低SOH告警与恢复() {
        when(batteryDao.selectList(any())).thenReturn(List.of(battery("BAT-LOW", 70)),
                List.of(battery("BAT-OK", 95)));

        task.scan();

        verify(alarmService).raise(eq(AlarmService.DEVICE_BATTERY), eq("BAT-LOW"),
                eq(AlarmType.BATTERY_HEALTH_LOW), anyString());
        verify(alarmService).markRecovered(AlarmService.DEVICE_BATTERY, "BAT-OK",
                AlarmType.BATTERY_HEALTH_LOW);
        verify(watchdog).beat("battery-health-scan");
    }

    @Test
    @DisplayName("租约锁未抢到：跳过本轮")
    void 未抢到锁跳过() {
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenReturn(false);

        task.scan();

        verifyNoInteractions(batteryDao, alarmService);
        verify(watchdog, never()).beat(anyString());
    }
}
