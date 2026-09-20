package com.swapops.server.charge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import com.swapops.server.charge.dao.ChargePolicyDao;
import com.swapops.server.charge.entity.ChargePolicyEntity;
import com.swapops.server.charge.form.ChargePolicyForm;
import com.swapops.server.common.RRException;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import com.swapops.server.device.service.CommandDispatchService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 充电策略数据范围（批次43 补：独立审计 F-03）。
 *
 * <p>此前 apply/reapply/list 都按<b>请求参数里的 cabinetNo</b> 定位资源，没有任何站点校验：
 * 站点范围的 OPS 可以改别人站点柜子的充电窗口/功率并真实下发指令，也能读到别人的策略史。
 */
@DisplayName("充电策略数据范围（F-03：不得跨站下发/查看）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargePolicyScopeTest {

    @Mock
    private ChargePolicyDao policyDao;
    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private CommandDispatchService dispatchService;

    private ChargePolicyService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ChargePolicyEntity.class);
        TableInfoHelper.initTableInfo(assistant, CabinetEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new ChargePolicyService(policyDao, cabinetDao, dispatchService);
        CommandLogEntity cmd = new CommandLogEntity();
        cmd.setId(1L);
        cmd.setCommandSeq(9L);
        when(dispatchService.preparePolicy(anyString(), anyString())).thenReturn(cmd);
    }

    @AfterEach
    void tearDown() {
        AdminContext.set(null);
    }

    private void stationScoped(Long... stationIds) {
        AdminContext.set(new AdminContext.Principal(9L, "ops01", AdminRole.OPS, false,
                "STATION", Set.of(stationIds)));
    }

    private CabinetEntity cabinet(String no, Long stationId) {
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(stationId * 10);
        cabinet.setCabinetNo(no);
        cabinet.setStationId(stationId);
        return cabinet;
    }

    private ChargePolicyForm form(String cabinetNo) {
        ChargePolicyForm.Window window = new ChargePolicyForm.Window();
        window.setStartHour(0);
        window.setEndHour(24);
        window.setPowerLimitW(7);
        window.setFeeFenPerKwh(100);
        ChargePolicyForm form = new ChargePolicyForm();
        form.setCabinetNo(cabinetNo);
        form.setWindows(List.of(window));
        form.setPriority(1);
        return form;
    }

    @Test
    @DisplayName("apply：柜在域外 ⇒ 403，且不下发任何指令")
    void 跨域下发拒绝() {
        stationScoped(7L);
        when(cabinetDao.selectOne(any())).thenReturn(cabinet("SWAP-C-099", 99L));

        assertThatThrownBy(() -> service.apply(form("SWAP-C-099")))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无权访问该站点数据");
        verify(dispatchService, never()).dispatchPolicy(any(), anyString(), anyLong(), any());
        verify(policyDao, never()).insert(any(ChargePolicyEntity.class));
    }

    @Test
    @DisplayName("apply：本域柜放行并正常下发")
    void 本域下发放行() {
        stationScoped(7L);
        when(cabinetDao.selectOne(any())).thenReturn(cabinet("SWAP-C-001", 7L));
        when(policyDao.selectOne(any())).thenReturn(null);

        assertThat(service.apply(form("SWAP-C-001")).getCabinetNo()).isEqualTo("SWAP-C-001");
        verify(dispatchService).dispatchPolicy(any(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("reapply：策略所属柜在域外 ⇒ 403（重投也是一次真实下发）")
    void 跨域重投拒绝() {
        stationScoped(7L);
        ChargePolicyEntity policy = new ChargePolicyEntity();
        policy.setId(5L);
        policy.setCabinetNo("SWAP-C-099");
        policy.setVersion(2);
        policy.setStatus(2);
        when(policyDao.selectById(5L)).thenReturn(policy);
        when(cabinetDao.selectOne(any())).thenReturn(cabinet("SWAP-C-099", 99L));

        assertThatThrownBy(() -> service.reapply(5L))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无权访问该站点数据");
        verify(dispatchService, never()).dispatchPolicy(any(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("list：指定域外柜号 ⇒ 403")
    void 跨域查看拒绝() {
        stationScoped(7L);
        when(cabinetDao.selectOne(any())).thenReturn(cabinet("SWAP-C-099", 99L));

        assertThatThrownBy(() -> service.list("SWAP-C-099"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无权访问该站点数据");
    }

    @Test
    @DisplayName("list：不指定柜号时按范围内柜号收敛（不是查全量再过滤）")
    void 列表按范围收敛() {
        stationScoped(7L);
        when(cabinetDao.selectList(any())).thenReturn(List.of(cabinet("SWAP-C-001", 7L)));
        when(policyDao.selectList(any())).thenReturn(List.of());

        service.list(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ChargePolicyEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(policyDao).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("cabinet_no");
    }

    @Test
    @DisplayName("list：范围内一个柜都没有 ⇒ 空列表（fail-closed）")
    void 空范围空列表() {
        stationScoped();
        when(cabinetDao.selectList(any())).thenReturn(List.of());

        assertThat(service.list(null)).isEmpty();
        verify(policyDao, never()).selectList(any());
    }
}
