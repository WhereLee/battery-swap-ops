package com.swapops.server.transfer.service;

import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.asset.entity.StationEntity;
import com.swapops.server.common.RRException;
import com.swapops.server.common.id.SnowflakeIdGenerator;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.order.service.AllocationService;
import com.swapops.server.transfer.config.TransferProperties;
import com.swapops.server.transfer.dao.TransferTaskDao;
import com.swapops.server.transfer.dao.TransferTaskItemDao;
import com.swapops.server.transfer.entity.TransferTaskEntity;
import com.swapops.server.transfer.enums.TransferStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调拨数据范围（批次43 补：独立审计 F-01，P0）。
 *
 * <p>此前调拨模块<b>完全没有</b>数据范围校验，站点只从请求参数取：站点范围身份可以对任意站点建单，
 * 再 approve + out 把别人的电池物理取走。本组钉住三条规则：
 * <ul>
 *   <li>建单：<b>调出站</b>必须在范围内（电池从那里拿走），调入站允许在范围外（调拨的意义）；</li>
 *   <li>出库：必须对<b>调出站</b>有范围；入库：必须对<b>调入站</b>有范围；</li>
 *   <li>读/审批/取消：在<b>任一侧</b>范围内即可见（跨站任务对参与方可见）。</li>
 * </ul>
 */
@DisplayName("调拨数据范围（F-01：跨站取电池必须被拦）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferScopeTest {

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
    private SnowflakeIdGenerator idGenerator;

    private TransferService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 纯 Mockito 测试里没有 MyBatis 启动流程，而 page() 用 lambda 构造 wrapper，
        // 需要实体元数据缓存（与 ChargePolicyServiceTest 同法）。
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, TransferTaskEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.swapops.server.transfer.entity.TransferTaskItemEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new TransferService(taskDao, itemDao, stationDao, cabinetDao, cellDao, batteryDao,
                allocationService, idGenerator, new TransferProperties(), new DeviceChannelProperties());
    }

    @AfterEach
    void tearDown() {
        AdminContext.set(null);
    }

    private void stationScoped(Long... stationIds) {
        AdminContext.set(new AdminContext.Principal(9L, "ops01", AdminRole.OPS, false,
                "STATION", Set.of(stationIds)));
    }

    private TransferTaskEntity task(Long fromStation, Long toStation) {
        TransferTaskEntity task = new TransferTaskEntity();
        task.setId(1L);
        task.setTaskNo("TR1");
        task.setFromStation(fromStation);
        task.setToStation(toStation);
        task.setStatus(TransferStatus.DRAFT.getCode());
        return task;
    }

    @Test
    @DisplayName("建单：调出站在范围外 ⇒ 403，且不落任何任务")
    void 建单跨域调出站拒绝() {
        stationScoped(7L);
        StationEntity other = new StationEntity();
        other.setId(99L);
        other.setStatus(1);
        when(stationDao.selectById(99L)).thenReturn(other);

        assertThatThrownBy(() -> service.create(99L, 7L, 1, "ops01"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无权访问该站点数据");
        verify(taskDao, never()).insert(any(TransferTaskEntity.class));
    }

    @Test
    @DisplayName("建单：调出站在范围内、调入站在范围外 ⇒ 放行（调拨的意义就是从别站调电）")
    void 建单允许调入站在域外() {
        stationScoped(7L);
        StationEntity mine = new StationEntity();
        mine.setId(7L);
        mine.setStatus(1);
        StationEntity other = new StationEntity();
        other.setId(99L);
        other.setStatus(1);
        when(stationDao.selectById(7L)).thenReturn(mine);
        when(stationDao.selectById(99L)).thenReturn(other);
        // 调出站没有可用满电电池：这一步就返回，足以证明范围校验放行（否则会先抛 403）
        when(cabinetDao.selectList(any())).thenReturn(java.util.List.of());
        when(idGenerator.nextIdString()).thenReturn("1");

        assertThatThrownBy(() -> service.create(7L, 99L, 1, "ops01"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("可用满电电池不足");
    }

    @Test
    @DisplayName("出库：对调出站无范围 ⇒ 403（物理动作按单侧从严）")
    void 出库跨域拒绝() {
        stationScoped(7L);
        when(taskDao.selectById(1L)).thenReturn(task(99L, 7L)); // 调入站是我的，调出站不是

        assertThatThrownBy(() -> service.out(1L, "BAT-1", "ops01"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无权访问该站点数据");
        verify(batteryDao, never()).update(any(), any());
    }

    @Test
    @DisplayName("入库：对调入站无范围 ⇒ 403")
    void 入库跨域拒绝() {
        stationScoped(7L);
        when(taskDao.selectById(1L)).thenReturn(task(7L, 99L)); // 调出站是我的，调入站不是

        assertThatThrownBy(() -> service.in(1L, "BAT-1", 202L, "ops01"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无权访问该站点数据");
    }

    @Test
    @DisplayName("详情：两侧都不在范围 ⇒ 403；任一侧在范围 ⇒ 可见")
    void 详情参与者可见() {
        stationScoped(7L);
        when(taskDao.selectById(1L)).thenReturn(task(99L, 98L));
        assertThatThrownBy(() -> service.detail(1L))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无权访问该调拨任务");

        when(taskDao.selectById(2L)).thenReturn(task(99L, 7L));
        when(itemDao.selectList(any())).thenReturn(java.util.List.of());
        TransferTaskEntity visible = task(99L, 7L);
        visible.setId(2L);
        when(taskDao.selectById(2L)).thenReturn(visible);
        assertThat(service.detail(2L)).containsKey("task");
    }

    @Test
    @DisplayName("列表：受限身份的查询必须带上 from/to 的范围条件（不是查全量再过滤）")
    void 列表带范围条件() {
        stationScoped(7L);
        when(taskDao.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.page(1, 10, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransferTaskEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(taskDao).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("from_station").contains("to_station").contains("OR");
    }

    @Test
    @DisplayName("建议列表：范围为空 ⇒ 直接空列表（fail-closed，不去查全站供需）")
    void 建议列表空范围() {
        stationScoped();
        assertThat(service.recommend()).isEmpty();
        verify(stationDao, never()).selectList(any());
    }
}
