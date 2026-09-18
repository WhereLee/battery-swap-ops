package com.swapops.server.asset.service;

import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 设备归属解析测试：柜号 / 仓级号 / 电池三级链 / 在途电池 / 未知设备号。 */
@DisplayName("设备归属解析（deviceNo → stationId）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceOwnershipServiceTest {

    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private BatteryDao batteryDao;
    @Mock
    private CellDao cellDao;

    private CabinetEntity cabinet(Long stationId) {
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(11L);
        cabinet.setCabinetNo("SWAP-C-005");
        cabinet.setStationId(stationId);
        return cabinet;
    }

    private DeviceOwnershipService service() {
        return new DeviceOwnershipService(cabinetDao, batteryDao, cellDao);
    }

    @Test
    @DisplayName("柜号直接命中")
    void 柜号命中() {
        when(cabinetDao.selectOne(any())).thenReturn(cabinet(7L));
        assertThat(service().resolveStationId("SWAP-C-005")).isEqualTo(7L);
    }

    @Test
    @DisplayName("仓级设备号（SWAP-C-005-3）截末段后命中")
    void 仓级号截断命中() {
        when(cabinetDao.selectOne(any())).thenReturn(null, cabinet(7L));
        assertThat(service().resolveStationId("SWAP-C-005-3")).isEqualTo(7L);
    }

    @Test
    @DisplayName("电池号经 电池→仓→柜 三级解析命中")
    void 电池三级链命中() {
        BatteryEntity battery = new BatteryEntity();
        battery.setBatteryNo("BAT-0049");
        battery.setCellId(88L);
        CellEntity cell = new CellEntity();
        cell.setId(88L);
        cell.setCabinetId(11L);
        when(cabinetDao.selectById(11L)).thenReturn(cabinet(3L));
        when(batteryDao.selectOne(any())).thenReturn(battery);
        when(cellDao.selectById(88L)).thenReturn(cell);

        assertThat(service().resolveStationId("BAT-0049")).isEqualTo(3L);
    }

    @Test
    @DisplayName("在途电池（无所在仓）→ 无归属 null（不猜、不抛）")
    void 在途电池无归属() {
        BatteryEntity battery = new BatteryEntity();
        battery.setBatteryNo("BAT-0049");
        battery.setCellId(null);
        when(batteryDao.selectOne(any())).thenReturn(battery);

        assertThat(service().resolveStationId("BAT-0049")).isNull();
    }

    @Test
    @DisplayName("系统级/跨站设备号（SITE-A、woNo）与空值 → null")
    void 无归属形态() {
        assertThat(service().resolveStationId("SITE-A")).isNull();
        assertThat(service().resolveStationId("WO93920459044814848")).isNull();
        assertThat(service().resolveStationId("  ")).isNull();
        assertThat(service().resolveStationId(null)).isNull();
    }
}
