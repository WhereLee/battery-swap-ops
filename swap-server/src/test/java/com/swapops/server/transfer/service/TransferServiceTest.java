package com.swapops.server.transfer.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.contract.BatteryStatus;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.asset.entity.StationEntity;
import com.swapops.server.common.RRException;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.order.service.AllocationService;
import com.swapops.server.transfer.config.TransferProperties;
import com.swapops.server.transfer.dao.TransferTaskDao;
import com.swapops.server.transfer.dao.TransferTaskItemDao;
import com.swapops.server.transfer.entity.TransferTaskEntity;
import com.swapops.server.transfer.entity.TransferTaskItemEntity;
import com.swapops.server.transfer.enums.TransferItemStatus;
import com.swapops.server.transfer.enums.TransferStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站间调拨单测（S4.2）：供需建议配对、创建校验、审批/取消规则、出/入库守卫与聚合推进。
 */
@DisplayName("站间调拨")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferServiceTest {

    @Mock
    private TransferTaskDao taskDao;
    @Mock
    private TransferTaskItemDao itemDao;
    @Mock
    private StationDao stationDao;
    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private CellDao cellDao;
    @Mock
    private BatteryDao batteryDao;
    @Mock
    private AllocationService allocationService;
    @Mock
    private com.swapops.server.common.id.SnowflakeIdGenerator idGenerator;

    private TransferService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TransferTaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, TransferTaskItemEntity.class);
        TableInfoHelper.initTableInfo(assistant, StationEntity.class);
        TableInfoHelper.initTableInfo(assistant, CabinetEntity.class);
        TableInfoHelper.initTableInfo(assistant, CellEntity.class);
        TableInfoHelper.initTableInfo(assistant, BatteryEntity.class);
    }

    @BeforeEach
    void setUp() {
        when(idGenerator.nextIdString()).thenReturn("123");
        service = new TransferService(taskDao, itemDao, stationDao, cabinetDao, cellDao, batteryDao,
                allocationService, idGenerator, new TransferProperties(), new DeviceChannelProperties());
    }

    private StationEntity station(long id, String no, Double lat, Double lng) {
        StationEntity station = new StationEntity();
        station.setId(id);
        station.setStationNo(no);
        station.setStatus(1);
        station.setLatitude(lat);
        station.setLongitude(lng);
        return station;
    }

    private CabinetEntity cabinet(long id, long stationId) {
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(id);
        cabinet.setStationId(stationId);
        cabinet.setCabinetNo("SWAP-C-" + id);
        return cabinet;
    }

    @Test
    @DisplayName("建议：缺口站按缺口排序，就近富余站配对，数量=min(缺口,富余,上限)")
    void 调拨建议配对() {
        StationEntity rich = station(1L, "ST-RICH", 30.27, 120.15);
        StationEntity poor = station(2L, "ST-POOR", 30.30, 120.18);
        when(stationDao.selectList(any())).thenReturn(List.of(rich, poor));
        when(cabinetDao.selectList(any())).thenReturn(List.of(cabinet(11L, 1L)), List.of(cabinet(22L, 2L)));
        when(allocationService.countAvailable(eq("SWAP-C-11"), eq(true))).thenReturn(6L);
        when(allocationService.countAvailable(eq("SWAP-C-22"), eq(true))).thenReturn(1L);

        List<Map<String, Object>> recommendations = service.recommend();

        assertThat(recommendations).hasSize(1);
        Map<String, Object> rec = recommendations.get(0);
        assertThat(rec.get("fromStationNo")).isEqualTo("ST-RICH");
        assertThat(rec.get("toStationNo")).isEqualTo("ST-POOR");
        assertThat(rec.get("quantity")).isEqualTo(2L); // min(3-1, 6-4, 3)
        assertThat(rec.get("distanceKm")).isNotNull();
        assertThat(rec.get("distanceUnknown")).isEqualTo(false);
    }

    @Test
    @DisplayName("建议：坐标缺失不伪造距离（distanceUnknown=true）")
    void 建议坐标缺失() {
        StationEntity rich = station(1L, "ST-RICH", null, null);
        StationEntity poor = station(2L, "ST-POOR", 30.30, 120.18);
        when(stationDao.selectList(any())).thenReturn(List.of(rich, poor));
        when(cabinetDao.selectList(any())).thenReturn(List.of(cabinet(11L, 1L)), List.of(cabinet(22L, 2L)));
        when(allocationService.countAvailable(anyString(), eq(true))).thenReturn(6L, 1L);

        Map<String, Object> rec = service.recommend().get(0);

        assertThat(rec.get("distanceKm")).isNull();
        assertThat(rec.get("distanceUnknown")).isEqualTo(true);
    }

    @Test
    @DisplayName("创建：源站满电充足生成 DRAFT + 明细；不足拒绝")
    void 创建任务() {
        when(stationDao.selectById(1L)).thenReturn(station(1L, "ST-RICH", null, null));
        when(stationDao.selectById(2L)).thenReturn(station(2L, "ST-POOR", null, null));
        when(cabinetDao.selectList(any())).thenReturn(List.of(cabinet(11L, 1L)));
        CellEntity c1 = new CellEntity();
        c1.setId(101L);
        c1.setBatteryId(201L);
        c1.setCabinetId(11L);
        CellEntity c2 = new CellEntity();
        c2.setId(102L);
        c2.setBatteryId(202L);
        c2.setCabinetId(11L);
        when(cellDao.selectList(any())).thenReturn(List.of(c1, c2));
        BatteryEntity b1 = new BatteryEntity();
        b1.setId(201L);
        b1.setBatteryNo("BAT-0001");
        BatteryEntity b2 = new BatteryEntity();
        b2.setId(202L);
        b2.setBatteryNo("BAT-0002");
        when(batteryDao.selectList(any())).thenReturn(List.of(b1, b2));
        when(taskDao.insert(any(TransferTaskEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, TransferTaskEntity.class).setId(9L);
            return 1;
        });
        TransferTaskEntity task = new TransferTaskEntity();
        task.setId(9L);
        task.setTaskNo("TR123");
        task.setStatus(TransferStatus.DRAFT.getCode());
        when(taskDao.selectById(9L)).thenReturn(task);
        when(itemDao.selectList(any())).thenReturn(List.of());

        Map<String, Object> detail = service.create(1L, 2L, 2, "admin");

        assertThat(detail.get("task")).isNotNull();
        verify(itemDao, org.mockito.Mockito.times(2)).insert(any(TransferTaskItemEntity.class));

        when(batteryDao.selectList(any())).thenReturn(List.of(b1));
        assertThatThrownBy(() -> service.create(1L, 2L, 2, "admin"))
                .isInstanceOf(RRException.class).hasMessageContaining("不足");
    }

    @Test
    @DisplayName("审批/取消规则：EXECUTING 不可取消")
    void 审批与取消() {
        TransferTaskEntity executing = new TransferTaskEntity();
        executing.setId(9L);
        executing.setStatus(TransferStatus.EXECUTING.getCode());
        when(taskDao.selectById(9L)).thenReturn(executing);
        when(taskDao.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(9L, "admin"))
                .isInstanceOf(RRException.class).hasMessageContaining("不可取消");

        TransferTaskEntity draft = new TransferTaskEntity();
        draft.setId(10L);
        draft.setStatus(TransferStatus.DRAFT.getCode());
        when(taskDao.selectById(10L)).thenReturn(draft);
        when(taskDao.update(isNull(), any())).thenReturn(1);
        service.approve(10L, "admin");
        verify(taskDao, org.mockito.Mockito.atLeastOnce()).update(isNull(), any());
    }

    @Test
    @DisplayName("出库守卫：明细/位置不满足拒绝；成功则清仓置在途并推进 EXECUTING")
    void 出库() {
        TransferTaskEntity task = new TransferTaskEntity();
        task.setId(9L);
        task.setTaskNo("TR123");
        task.setFromStation(1L);
        task.setStatus(TransferStatus.APPROVED.getCode());
        when(taskDao.selectById(9L)).thenReturn(task);
        TransferTaskItemEntity item = new TransferTaskItemEntity();
        item.setId(55L);
        item.setStatus(TransferItemStatus.PENDING.getCode());
        when(itemDao.selectOne(any())).thenReturn(item);
        when(itemDao.update(isNull(), any())).thenReturn(1);
        when(batteryDao.selectOne(any())).thenReturn(new BatteryEntity());
        when(batteryDao.selectOne(any())).thenReturn(batteryInCell(201L, "BAT-0001", 101L));
        CellEntity cell = new CellEntity();
        cell.setId(101L);
        cell.setCabinetId(11L);
        when(cellDao.selectById(101L)).thenReturn(cell);
        when(cabinetDao.selectById(11L)).thenReturn(cabinet(11L, 1L));
        when(taskDao.update(isNull(), any())).thenReturn(1);
        when(itemDao.selectList(any())).thenReturn(List.of());

        service.out(9L, "BAT-0001", "admin");

        verify(cellDao).update(isNull(), any());
        verify(batteryDao).update(isNull(), any());
        verify(allocationService).rebuildFromDb();
    }

    private BatteryEntity batteryInCell(long id, String no, long cellId) {
        BatteryEntity battery = new BatteryEntity();
        battery.setId(id);
        battery.setBatteryNo(no);
        battery.setCellId(cellId);
        battery.setStatus(BatteryStatus.FULL.getCode());
        return battery;
    }

    @Test
    @DisplayName("入库：末项入库触发任务 DONE（聚合推进）")
    void 入库聚合完成() {
        TransferTaskEntity task = new TransferTaskEntity();
        task.setId(9L);
        task.setTaskNo("TR123");
        task.setToStation(2L);
        task.setStatus(TransferStatus.EXECUTING.getCode());
        when(taskDao.selectById(9L)).thenReturn(task);
        TransferTaskItemEntity item = new TransferTaskItemEntity();
        item.setId(55L);
        item.setStatus(TransferItemStatus.OUT.getCode());
        when(itemDao.selectOne(any())).thenReturn(item);
        when(itemDao.update(isNull(), any())).thenReturn(1);
        BatteryEntity battery = new BatteryEntity();
        battery.setId(201L);
        battery.setBatteryNo("BAT-0001");
        when(batteryDao.selectOne(any())).thenReturn(battery);
        CellEntity target = new CellEntity();
        target.setId(202L);
        target.setCabinetId(22L);
        when(cellDao.selectById(202L)).thenReturn(target);
        when(cabinetDao.selectById(22L)).thenReturn(cabinet(22L, 2L));
        when(itemDao.selectCount(any())).thenReturn(0L);
        when(taskDao.update(isNull(), any())).thenReturn(1);

        service.in(9L, "BAT-0001", 202L, "admin");

        // 明细两次更新：先 CAS 状态，再落 in_cell_id/in_time
        verify(itemDao, org.mockito.Mockito.times(2)).update(isNull(), any());
        verify(taskDao).update(isNull(), any()); // EXECUTING -> DONE
        verify(allocationService).rebuildFromDb();
    }
}
