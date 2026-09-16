package com.swapops.server.dashboard.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.contract.BatteryStatus;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.asset.entity.StationEntity;
import com.swapops.server.common.cache.TwoLevelCacheService;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.order.dao.SwapOrderDao;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 看板指标单测（S4.5）：口径计算（保有率/可用率/周转率）、除零、缓存 TTL 传参。
 */
@DisplayName("运营看板指标")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTest {

    @Mock
    private BatteryDao batteryDao;
    @Mock
    private StationDao stationDao;
    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private CellDao cellDao;
    @Mock
    private SwapOrderDao orderDao;
    @Mock
    private AllocationService allocationService;
    @Mock
    private TwoLevelCacheService cache;

    private DashboardService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BatteryEntity.class);
        TableInfoHelper.initTableInfo(assistant, StationEntity.class);
        TableInfoHelper.initTableInfo(assistant, CabinetEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.device.entity.CellEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.order.entity.SwapOrderEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new DashboardService(batteryDao, stationDao, cabinetDao, cellDao, orderDao,
                allocationService, cache);
        when(cache.getEntity(any(), eq(Map.class), any(), eq(DashboardService.CACHE_TTL_SECONDS)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(2)).get());
    }

    private StationEntity station(long id, String no) {
        StationEntity station = new StationEntity();
        station.setId(id);
        station.setStationNo(no);
        station.setStatus(1);
        return station;
    }

    private CabinetEntity cabinet(String no) {
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setCabinetNo(no);
        return cabinet;
    }

    @Test
    @DisplayName("口径计算：保有率/可用率/周转率")
    void 指标计算() {
        when(batteryDao.selectCount(any())).thenReturn(50L, 20L);
        when(stationDao.selectList(any())).thenReturn(List.of(station(1L, "ST-001"), station(2L, "ST-002")));
        when(cabinetDao.selectList(any())).thenReturn(List.of(cabinet("SWAP-C-001")));
        when(allocationService.countAvailable(any(), eq(true))).thenReturn(5L, 0L);
        when(orderDao.selectCount(any())).thenReturn(10L);

        Map<String, Object> result = service.overview();

        assertThat(result.get("totalBatteries")).isEqualTo(50L);
        assertThat(result.get("fullBatteryRate")).isEqualTo(0.4);
        assertThat(result.get("stationAvailabilityRate")).isEqualTo(0.5);
        assertThat(result.get("unavailableStations")).isEqualTo(List.of("ST-002"));
        assertThat(result.get("completedToday")).isEqualTo(10L);
        assertThat(result.get("turnoverRate")).isEqualTo(0.2);
    }

    @Test
    @DisplayName("除零保护：无电池/无站点时指标为 0")
    void 除零保护() {
        when(batteryDao.selectCount(any())).thenReturn(0L, 0L);
        when(stationDao.selectList(any())).thenReturn(List.of());
        when(orderDao.selectCount(any())).thenReturn(0L);

        Map<String, Object> result = service.overview();

        assertThat(result.get("fullBatteryRate")).isEqualTo(0.0);
        assertThat(result.get("stationAvailabilityRate")).isEqualTo(0.0);
        assertThat(result.get("turnoverRate")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("缓存：以 60s TTL 走两级缓存")
    void 缓存TTL() {
        when(batteryDao.selectCount(any())).thenReturn(1L, 1L);
        when(stationDao.selectList(any())).thenReturn(List.of());
        when(orderDao.selectCount(any())).thenReturn(0L);

        service.overview();

        org.mockito.Mockito.verify(cache).getEntity(eq(
                        com.swapops.server.common.cache.CacheKeys.DASHBOARD_OVERVIEW),
                eq(Map.class), any(), eq(DashboardService.CACHE_TTL_SECONDS));
    }

    @Test
    @DisplayName("P1-8 数据范围：受限身份绕过全局缓存直算本域口径（站点/电池/订单按范围过滤）")
    void 受限身份绕过缓存() {
        AdminContext.set(new AdminContext.Principal(9L, "ops-st", AdminRole.OPS, false, "STATION", Set.of(1L)));
        try {
            CabinetEntity cabinet = cabinet("SWAP-C-001");
            cabinet.setId(11L);
            cabinet.setStationId(1L);
            com.swapops.server.device.entity.CellEntity cell = new com.swapops.server.device.entity.CellEntity();
            cell.setId(111L);
            cell.setCabinetId(11L);
            when(cabinetDao.selectList(any())).thenReturn(List.of(cabinet));
            when(cellDao.selectList(any())).thenReturn(List.of(cell));
            when(batteryDao.selectCount(any())).thenReturn(12L, 5L);
            when(stationDao.selectList(any())).thenReturn(List.of(station(1L, "ST-001")));
            when(allocationService.countAvailable(any(), eq(true))).thenReturn(4L);
            when(orderDao.selectCount(any())).thenReturn(7L);

            Map<String, Object> result = service.overview();

            assertThat(result.get("totalStations")).isEqualTo(1);
            assertThat(result.get("totalBatteries")).isEqualTo(12L);
            assertThat(result.get("fullBatteries")).isEqualTo(5L);
            assertThat(result.get("completedToday")).isEqualTo(7L);
            org.mockito.Mockito.verify(cache, org.mockito.Mockito.never())
                    .getEntity(any(), eq(Map.class), any(), eq(DashboardService.CACHE_TTL_SECONDS));
        } finally {
            AdminContext.set(null);
        }
    }
}
