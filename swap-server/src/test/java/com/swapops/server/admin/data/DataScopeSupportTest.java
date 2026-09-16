package com.swapops.server.admin.data;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.asset.entity.StationEntity;
import com.swapops.server.common.RRException;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据范围注入工具单测（P1-8）：非受限直通 / 受限追加 IN 或恒假 / 资源级 403 /
 * 两级解析（柜/仓）/ 站点编号解析 fail-closed。
 */
@DisplayName("数据范围注入工具")
class DataScopeSupportTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, StationEntity.class);
        TableInfoHelper.initTableInfo(assistant, CabinetEntity.class);
        TableInfoHelper.initTableInfo(assistant, CellEntity.class);
        TableInfoHelper.initTableInfo(assistant, BatteryEntity.class);
    }

    @AfterEach
    void cleanup() {
        AdminContext.set(null);
    }

    private void scopedAll() {
        AdminContext.set(new AdminContext.Principal(9L, "ops-all", AdminRole.OPS, false, "ALL", null));
    }

    private void scopedStation(Long... stationIds) {
        AdminContext.set(new AdminContext.Principal(9L, "ops-st", AdminRole.OPS, false, "STATION",
                Set.of(stationIds)));
    }

    private void scopedStationEmpty() {
        AdminContext.set(new AdminContext.Principal(9L, "ops-st", AdminRole.OPS, false, "STATION", Set.of()));
    }

    @Test
    @DisplayName("非受限身份（无上下文/ALL/bootstrap）：不追加任何条件")
    void 非受限不追加() {
        LambdaQueryWrapper<StationEntity> wrapper = new LambdaQueryWrapper<>();
        DataScopeSupport.applyStation(wrapper, StationEntity::getId);
        assertThat(DataScopeSupport.stationIdsOrNull()).isNull();
        wrapper.getSqlSegment(); // 触发渲染（MP 参数在段渲染时登记）
        assertThat(wrapper.getParamNameValuePairs()).isEmpty();

        scopedAll();
        LambdaQueryWrapper<StationEntity> all = new LambdaQueryWrapper<>();
        DataScopeSupport.applyStation(all, StationEntity::getId);
        assertThat(DataScopeSupport.stationIdsOrNull()).isNull();
        all.getSqlSegment();
        assertThat(all.getParamNameValuePairs()).isEmpty();

        AdminContext.set(new AdminContext.Principal(null, "bootstrap", AdminRole.SUPER, true, "ALL", null));
        assertThat(DataScopeSupport.stationIdsOrNull()).isNull();
    }

    @Test
    @DisplayName("STATION 非空范围：追加 IN（站点 id 集）")
    void 受限追加IN() {
        scopedStation(1L, 2L);
        LambdaQueryWrapper<StationEntity> wrapper = new LambdaQueryWrapper<>();
        DataScopeSupport.applyStation(wrapper, StationEntity::getId);
        wrapper.getSqlSegment();
        assertThat(wrapper.getParamNameValuePairs().values()).contains(1L, 2L);

        LambdaQueryWrapper<BatteryEntity> battery = new LambdaQueryWrapper<>();
        DataScopeSupport.applyIds(battery, BatteryEntity::getCellId, Set.of(11L, 12L));
        battery.getSqlSegment();
        assertThat(battery.getParamNameValuePairs().values()).contains(11L, 12L);
    }

    @Test
    @DisplayName("STATION 空范围：恒假条件（-1），不可见任何行")
    void 空范围恒假() {
        scopedStationEmpty();
        assertThat(DataScopeSupport.stationIdsOrNull()).isEmpty();
        LambdaQueryWrapper<StationEntity> wrapper = new LambdaQueryWrapper<>();
        DataScopeSupport.applyStation(wrapper, StationEntity::getId);
        wrapper.getSqlSegment();
        assertThat(wrapper.getParamNameValuePairs().values()).containsExactly(-1L);

        LambdaQueryWrapper<CellEntity> cell = new LambdaQueryWrapper<>();
        DataScopeSupport.applyIds(cell, CellEntity::getCabinetId, Set.of());
        cell.getSqlSegment();
        assertThat(cell.getParamNameValuePairs().values()).containsExactly(-1L);
    }

    @Test
    @DisplayName("资源级：域内放行 / 越域 403 / 无归属 403 / 非受限放行")
    void 资源级校验() {
        // 非受限：一律放行（含 null 归属）
        DataScopeSupport.requireStationAccess(null);
        DataScopeSupport.requireStationAccess(1L);

        scopedStation(5L);
        DataScopeSupport.requireStationAccess(5L); // 域内放行

        assertThatThrownBy(() -> DataScopeSupport.requireStationAccess(6L))
                .isInstanceOf(RRException.class)
                .hasFieldOrPropertyWithValue("code", 403);
        assertThatThrownBy(() -> DataScopeSupport.requireStationAccess(null))
                .isInstanceOf(RRException.class)
                .hasFieldOrPropertyWithValue("code", 403);

        assertThatThrownBy(() -> DataScopeSupport.requireUnrestricted("创建站点"))
                .isInstanceOf(RRException.class)
                .hasFieldOrPropertyWithValue("code", 403);
        scopedAll();
        DataScopeSupport.requireUnrestricted("创建站点"); // 不受限身份放行
    }

    @Test
    @DisplayName("两级解析：站点范围 → 柜 id 集 → 仓 id 集")
    void 两级解析() {
        CabinetDao cabinetDao = mock(CabinetDao.class);
        CellDao cellDao = mock(CellDao.class);
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(101L);
        cabinet.setStationId(5L);
        CellEntity cell = new CellEntity();
        cell.setId(1001L);
        cell.setCabinetId(101L);
        when(cabinetDao.selectList(any())).thenReturn(List.of(cabinet));
        when(cellDao.selectList(any())).thenReturn(List.of(cell));

        // 非受限：null（不查库）
        assertThat(DataScopeSupport.cabinetIdsOrNull(cabinetDao)).isNull();
        assertThat(DataScopeSupport.cellIdsOrNull(cabinetDao, cellDao)).isNull();

        scopedStation(5L);
        assertThat(DataScopeSupport.cabinetIdsOrNull(cabinetDao)).containsExactly(101L);
        assertThat(DataScopeSupport.cellIdsOrNull(cabinetDao, cellDao)).containsExactly(1001L);

        scopedStationEmpty();
        assertThat(DataScopeSupport.cabinetIdsOrNull(cabinetDao)).isEmpty();
        assertThat(DataScopeSupport.cellIdsOrNull(cabinetDao, cellDao)).isEmpty();
    }

    @Test
    @DisplayName("站点编号解析：命中映射 / 空配置空集 / 解析异常 fail-closed 空集")
    void 站点编号解析() {
        StationDao stationDao = mock(StationDao.class);
        StationEntity s2 = new StationEntity();
        s2.setId(2L);
        s2.setStationNo("ST-002");
        when(stationDao.selectList(any())).thenReturn(List.of(s2));

        assertThat(DataScopeSupport.resolveScopeStationIds(stationDao, List.of("ST-002"))).containsExactly(2L);
        assertThat(DataScopeSupport.resolveScopeStationIds(stationDao, List.of())).isEmpty();
        assertThat(DataScopeSupport.resolveScopeStationIds(stationDao, null)).isEmpty();

        when(stationDao.selectList(any())).thenThrow(new RuntimeException("db down"));
        assertThat(DataScopeSupport.resolveScopeStationIds(stationDao, List.of("ST-002"))).isEmpty();
    }
}
