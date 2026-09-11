package com.swapops.server.device.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.contract.BatteryStatus;
import com.swapops.contract.CommandAction;
import com.swapops.contract.EventType;
import com.swapops.server.common.RRException;
import com.swapops.server.device.config.DeviceBootGenerationGuard;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.form.DeviceEventForm;
import com.swapops.server.order.service.AllocationService;
import com.swapops.server.order.service.OrderEventService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 事件编排单测：序守卫幂等丢弃、指令销账、归仓语义、协议垃圾拒绝。
 */
@DisplayName("设备事件编排（协议 v1）")
@ExtendWith(MockitoExtension.class)
class DeviceEventServiceTest {

    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private CellDao cellDao;
    @Mock
    private BatteryDao batteryDao;
    @Mock
    private CommandLogService commandLogService;
    @Mock
    private DeviceChannelProperties properties;
    @Mock
    private AllocationService allocationService;
    @Mock
    private OrderEventService orderEventService;
    @Mock
    private DeviceBootGenerationGuard bootGenerationGuard;
    @Mock
    private com.swapops.server.alarm.service.AlarmService alarmService;
    @InjectMocks
    private DeviceEventService service;

    /**
     * 纯单测无 MyBatis 装配：Lambda 包装器依赖 TableInfo 缓存（SerializedLambda 反解列名）
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CabinetEntity.class);
        TableInfoHelper.initTableInfo(assistant, CellEntity.class);
        TableInfoHelper.initTableInfo(assistant, BatteryEntity.class);
    }

    private DeviceEventForm form(EventType type, Integer cellNo, String batteryNo,
                                 String bootId, Long eventSeq, Long commandSeq, Integer soc) {
        DeviceEventForm form = new DeviceEventForm();
        form.setCabinetNo("SWAP-C-001");
        form.setEventType(type == null ? null : type.name());
        form.setCellNo(cellNo);
        form.setBatteryNo(batteryNo);
        form.setBootId(bootId);
        form.setEventSeq(eventSeq);
        form.setCommandSeq(commandSeq);
        form.setSoc(soc);
        return form;
    }

    @Test
    @DisplayName("序守卫拒绝（同代际旧序/重放）：幂等丢弃，不推进任何流水与状态")
    void 序守卫拒绝_幂等丢弃() {
        when(cabinetDao.update(isNull(), any())).thenReturn(0);
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setCabinetNo("SWAP-C-001");
        cabinet.setLastBootId("boot-1");
        cabinet.setLastEventSeq(9L);
        when(cabinetDao.selectOne(any())).thenReturn(cabinet);

        boolean accepted = service.handle(form(EventType.BATTERY_OUT, 3, "BAT-0001",
                "boot-1", 5L, 7L, null));

        assertThat(accepted).isFalse();
        verifyNoInteractions(cellDao, batteryDao, commandLogService);
    }

    @Test
    @DisplayName("DOOR_OPENED（携 commandSeq）：按 seq 精确销账指令流水")
    void 门开事件_销账指令() {
        when(cabinetDao.update(isNull(), any())).thenReturn(1);
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(1L);
        when(cabinetDao.selectOne(any())).thenReturn(cabinet);

        boolean accepted = service.handle(form(EventType.DOOR_OPENED, 3, null,
                "boot-1", 5L, 7L, null));

        assertThat(accepted).isTrue();
        verify(commandLogService).markArrivedBySeq("SWAP-C-001", 7L, CommandAction.OPEN_CELL);
        verify(orderEventService).onDoorOpened(any(CabinetEntity.class), eq(3), eq(7L));
        verify(bootGenerationGuard).register("SWAP-C-001", "boot-1");
    }

    @Test
    @DisplayName("跨代际重放（S3.1）：已见代际事件被拒——不推进台账/流水/订单")
    void 代际重放拒绝() {
        when(bootGenerationGuard.isReplay("SWAP-C-001", "boot-old")).thenReturn(true);

        boolean accepted = service.handle(form(EventType.DOOR_OPENED, 3, null,
                "boot-old", 9L, 7L, null));

        assertThat(accepted).isFalse();
        verifyNoInteractions(cabinetDao, cellDao, batteryDao, commandLogService, orderEventService);
    }

    @Test
    @DisplayName("BATTERY_IN（还电）：仓转占用 + 电池归仓（同事务两写）")
    void 还电_仓与电池双写() {
        when(cabinetDao.update(isNull(), any())).thenReturn(1);
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(1L);
        cabinet.setCabinetNo("SWAP-C-001");
        when(cabinetDao.selectOne(any())).thenReturn(cabinet);
        CellEntity cell = new CellEntity();
        cell.setId(11L);
        when(cellDao.selectOne(any())).thenReturn(cell);
        BatteryEntity battery = new BatteryEntity();
        battery.setId(21L);
        when(batteryDao.selectOne(any())).thenReturn(battery);

        boolean accepted = service.handle(form(EventType.BATTERY_IN, 1, "BAT-0001",
                "boot-1", 6L, null, 20));

        assertThat(accepted).isTrue();
        verify(cellDao).update(isNull(), any());
        verify(batteryDao).update(isNull(), any());
        verify(allocationService).onBatteryIn(11L);
        verify(orderEventService).onBatteryIn(any(CabinetEntity.class), any(CellEntity.class),
                any(BatteryEntity.class), eq(null));
    }

    @Test
    @DisplayName("协议垃圾（缺 eventSeq）：RRException，不触任何 DAO")
    void 缺eventSeq_协议拒绝() {
        assertThatThrownBy(() -> service.handle(form(EventType.BATTERY_OUT, 3, "BAT-0001",
                "boot-1", null, null, null)))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("缺必填字段");
        verifyNoInteractions(cabinetDao, cellDao, batteryDao);
    }

    @Test
    @DisplayName("未知事件类型：协议拒绝")
    void 未知事件类型_拒绝() {
        DeviceEventForm form = form(EventType.BATTERY_OUT, 3, "BAT-0001", "boot-1", 1L, null, null);
        form.setEventType("NOPE");
        assertThatThrownBy(() -> service.handle(form))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("未知事件类型");
        verifyNoInteractions(cabinetDao);
    }

    @Test
    @DisplayName("SOC_REPORT：借出电池只更新电量、不把状态改回在仓（防台账污染）")
    void soc借出电池_不改状态() {
        when(cabinetDao.update(isNull(), any())).thenReturn(1);
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(1L);
        when(cabinetDao.selectOne(any())).thenReturn(cabinet);
        BatteryEntity battery = new BatteryEntity();
        battery.setId(21L);
        battery.setStatus(BatteryStatus.LOANED.getCode());
        when(batteryDao.selectOne(any())).thenReturn(battery);

        service.handle(form(EventType.SOC_REPORT, 1, "BAT-0001", "boot-1", 7L, null, 55));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<BatteryEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(batteryDao).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("soc").doesNotContain("status");
    }

    @Test
    @DisplayName("SOC_REPORT：在仓电池达阈值转 FULL")
    void soc在仓电池_满电转FULL() {
        when(cabinetDao.update(isNull(), any())).thenReturn(1);
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(1L);
        when(cabinetDao.selectOne(any())).thenReturn(cabinet);
        BatteryEntity battery = new BatteryEntity();
        battery.setId(21L);
        battery.setStatus(BatteryStatus.CHARGING.getCode());
        when(batteryDao.selectOne(any())).thenReturn(battery);
        when(properties.getSocFullThreshold()).thenReturn(90);

        service.handle(form(EventType.SOC_REPORT, 1, "BAT-0001", "boot-1", 7L, null, 95));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<BatteryEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(batteryDao).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("status");
    }

    @Test
    @DisplayName("柜故障（S3.2）：中断在途指令 + 活跃订单转异常")
    void 柜故障事件_中断在途与订单() {
        when(cabinetDao.update(isNull(), any())).thenReturn(1);
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(1L);
        when(cabinetDao.selectOne(any())).thenReturn(cabinet);

        boolean accepted = service.handle(form(EventType.CABINET_FAULT, null, null, "boot-1", 8L, null, null));

        assertThat(accepted).isTrue();
        verify(commandLogService).markExecFailedByCabinet("SWAP-C-001");
        verify(orderEventService).onCabinetFault(cabinet);
    }

    @Test
    @DisplayName("未登记的柜事件：显式失败（配置错位）")
    void 未登记柜_显式失败() {
        when(cabinetDao.update(isNull(), any())).thenReturn(0);
        when(cabinetDao.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.handle(form(EventType.DOOR_OPENED, 1, null,
                "boot-1", 1L, null, null)))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("未登记的柜事件");
    }
}
