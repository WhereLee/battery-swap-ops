package com.swapops.server.asset.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.contract.BatteryStatus;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.asset.entity.StationEntity;
import com.swapops.server.asset.form.BatteryAdminForm;
import com.swapops.server.asset.form.CabinetAdminForm;
import com.swapops.server.asset.form.StationAdminForm;
import com.swapops.server.common.RRException;
import com.swapops.server.common.cache.CacheKeys;
import com.swapops.server.common.cache.TwoLevelCacheService;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.service.MonitorService;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.AllocationService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资产登记 CRUD 单测（S4.5 批二）：校验/唯一冲突/引用保护/secret 脱敏/仓缩减保护/池重建。
 */
@DisplayName("资产登记（站点/柜/仓/电池）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssetAdminServiceTest {

    @Mock
    private StationDao stationDao;
    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private CellDao cellDao;
    @Mock
    private BatteryDao batteryDao;
    @Mock
    private MonitorService monitorService;
    @Mock
    private AllocationService allocationService;
    @Mock
    private TwoLevelCacheService cache;
    @Mock
    private SwapOrderDao orderDao;

    private AssetAdminService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, StationEntity.class);
        TableInfoHelper.initTableInfo(assistant, CabinetEntity.class);
        TableInfoHelper.initTableInfo(assistant, CellEntity.class);
        TableInfoHelper.initTableInfo(assistant, BatteryEntity.class);
        TableInfoHelper.initTableInfo(assistant, SwapOrderEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new AssetAdminService(stationDao, cabinetDao, cellDao, batteryDao, monitorService,
                allocationService, cache, orderDao, new DeviceChannelProperties());
    }

    private StationEntity station() {
        StationEntity station = new StationEntity();
        station.setId(1L);
        station.setStationNo("ST-001");
        station.setStatus(1);
        return station;
    }

    private CabinetEntity cabinet(int cellCount) {
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(1L);
        cabinet.setCabinetNo("SWAP-C-011");
        cabinet.setStationId(1L);
        cabinet.setCellCount(cellCount);
        return cabinet;
    }

    private CellEntity cell(long id, int cellNo) {
        CellEntity cell = new CellEntity();
        cell.setId(id);
        cell.setCellNo(cellNo);
        cell.setCabinetId(1L);
        cell.setStatus(com.swapops.contract.CellStatus.EMPTY.getCode());
        return cell;
    }

    // ---------- 站点 ----------

    @Test
    @DisplayName("建站：落库 + 目录缓存失效；编号重复拒绝")
    void 建站() {
        StationAdminForm form = new StationAdminForm();
        form.setStationNo("ST-901");
        form.setName("联调站");
        form.setAddress("测试路 1 号");

        StationEntity created = service.createStation(form);

        assertThat(created.getStationNo()).isEqualTo("ST-901");
        assertThat(created.getStatus()).isEqualTo(1);
        verify(cache).evict(CacheKeys.STATION_ACTIVE_LIST);

        when(stationDao.selectOne(any())).thenReturn(station());
        assertThatThrownBy(() -> service.createStation(form))
                .isInstanceOf(RRException.class).hasMessageContaining("已存在");
    }

    @Test
    @DisplayName("删站：有柜引用拒绝；无引用删除")
    void 删站() {
        when(stationDao.selectById(1L)).thenReturn(station());
        when(cabinetDao.selectCount(any())).thenReturn(2L);
        assertThatThrownBy(() -> service.deleteStation(1L))
                .isInstanceOf(RRException.class).hasMessageContaining("不能删除");

        when(cabinetDao.selectCount(any())).thenReturn(0L);
        service.deleteStation(1L);
        verify(stationDao).deleteById(1L);
        verify(cache).evict(CacheKeys.STATION_ACTIVE_LIST);
    }

    // ---------- 柜 ----------

    @Test
    @DisplayName("建柜：自动建仓 + 池重建 + secret 脱敏；密钥非法拒绝")
    void 建柜() {
        when(stationDao.selectById(1L)).thenReturn(station());
        when(cabinetDao.insert(any(CabinetEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, CabinetEntity.class).setId(1L);
            return 1;
        });
        CabinetAdminForm form = new CabinetAdminForm();
        form.setCabinetNo("SWAP-C-011");
        form.setStationId(1L);
        form.setCellCount(3);
        form.setSecret("aabbccddeeff00112233445566778899");

        CabinetEntity created = service.createCabinet(form);

        verify(cellDao, times(3)).insert(any(CellEntity.class));
        verify(allocationService).rebuildFromDb();
        assertThat(created.getSecret()).isNull(); // 脱敏

        form.setSecret("bad");
        assertThatThrownBy(() -> service.createCabinet(form))
                .isInstanceOf(RRException.class).hasMessageContaining("密钥");
    }

    @Test
    @DisplayName("改柜：增仓补建；缩减遇有电池/有锁的仓拒绝")
    void 改柜增减仓() {
        when(cabinetDao.selectById(1L)).thenReturn(cabinet(3));
        when(cellDao.selectList(any())).thenReturn(List.of(cell(11L, 1), cell(12L, 2), cell(13L, 3)));

        CabinetAdminForm grow = new CabinetAdminForm();
        grow.setCellCount(5);
        service.updateCabinet(1L, grow);
        verify(cellDao, times(2)).insert(any(CellEntity.class));

        CellEntity locked = cell(13L, 3);
        locked.setLockOrderId(99L);
        when(cellDao.selectList(any())).thenReturn(List.of(cell(11L, 1), cell(12L, 2), locked));
        CabinetAdminForm shrink = new CabinetAdminForm();
        shrink.setCellCount(2);
        assertThatThrownBy(() -> service.updateCabinet(1L, shrink))
                .isInstanceOf(RRException.class).hasMessageContaining("缩减失败");
    }

    @Test
    @DisplayName("删柜：有电池拒绝；全空删除仓与柜 + 池重建")
    void 删柜() {
        when(cabinetDao.selectById(1L)).thenReturn(cabinet(2));
        CellEntity occupied = cell(11L, 1);
        occupied.setBatteryId(21L);
        when(cellDao.selectList(any())).thenReturn(List.of(occupied));
        assertThatThrownBy(() -> service.deleteCabinet(1L))
                .isInstanceOf(RRException.class).hasMessageContaining("不能删除");

        when(cellDao.selectList(any())).thenReturn(List.of(cell(11L, 1), cell(12L, 2)));
        service.deleteCabinet(1L);
        verify(cabinetDao).deleteById(1L);
        verify(allocationService).rebuildFromDb();
    }

    // ---------- 电池 ----------

    @Test
    @DisplayName("电池登记：放置到空仓（双向绑定）；占用仓拒绝")
    void 电池登记() {
        when(cellDao.selectById(10L)).thenReturn(cell(10L, 1));
        when(batteryDao.insert(any(BatteryEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, BatteryEntity.class).setId(21L);
            return 1;
        });
        BatteryAdminForm form = new BatteryAdminForm();
        form.setBatteryNo("BAT-9001");
        form.setSoc(100);
        form.setCellId(10L);

        BatteryEntity created = service.createBattery(form);

        assertThat(created.getStatus()).isEqualTo(BatteryStatus.FULL.getCode());
        verify(cellDao).update(any(), any());
        verify(allocationService).rebuildFromDb();

        CellEntity occupied = cell(10L, 1);
        occupied.setBatteryId(99L);
        when(cellDao.selectById(10L)).thenReturn(occupied);
        assertThatThrownBy(() -> service.createBattery(form))
                .isInstanceOf(RRException.class).hasMessageContaining("非空或已锁定");
    }

    @Test
    @DisplayName("转在途 + 标量编辑：先落标量再显式置空 cell（组合场景回归）")
    void 转在途保留标量编辑() {
        BatteryEntity inCell = new BatteryEntity();
        inCell.setId(30L);
        inCell.setBatteryNo("BAT-9003");
        inCell.setCellId(10L);
        inCell.setSoc(50);
        when(batteryDao.selectOne(any())).thenReturn(inCell);
        when(cellDao.selectById(10L)).thenReturn(cell(10L, 1));
        when(batteryDao.selectById(30L)).thenReturn(inCell);

        BatteryAdminForm form = new BatteryAdminForm();
        form.setPark(true);
        form.setSoc(80);
        service.updateBattery("BAT-9003", form);

        verify(batteryDao).updateById(any(BatteryEntity.class)); // 标量编辑落库
        verify(batteryDao, org.mockito.Mockito.atLeastOnce()).update(isNull(), any()); // 显式置空 cell
        verify(allocationService).rebuildFromDb();
    }

    @Test
    @DisplayName("电池编辑：在持拒绝；在途且无订单引用可删除")
    void 电池编辑与删除() {
        BatteryEntity held = new BatteryEntity();
        held.setId(21L);
        held.setBatteryNo("BAT-9001");
        held.setHolderUserId(7L);
        when(batteryDao.selectOne(any())).thenReturn(held);
        assertThatThrownBy(() -> service.updateBattery("BAT-9001", new BatteryAdminForm()))
                .isInstanceOf(RRException.class).hasMessageContaining("归还流程");

        BatteryEntity parked = new BatteryEntity();
        parked.setId(22L);
        parked.setBatteryNo("BAT-9002");
        when(batteryDao.selectOne(any())).thenReturn(parked);
        when(orderDao.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.deleteBattery("BAT-9002"))
                .isInstanceOf(RRException.class).hasMessageContaining("订单引用");

        when(orderDao.selectCount(any())).thenReturn(0L);
        service.deleteBattery("BAT-9002");
        verify(batteryDao).deleteById(22L);
        verify(batteryDao, never()).deleteById(21L);
    }
}
