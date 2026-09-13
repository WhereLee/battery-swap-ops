package com.swapops.server.charge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.charge.dao.ChargePolicyDao;
import com.swapops.server.charge.entity.ChargePolicyEntity;
import com.swapops.server.charge.form.ChargePolicyForm;
import com.swapops.server.common.RRException;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import com.swapops.server.device.service.CommandDispatchService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 充电策略服务单测（S4.3）：窗口校验（覆盖/重叠/范围）、版本递增、下发失败落 FAILED、重投规则。
 */
@DisplayName("充电策略")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargePolicyServiceTest {

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
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setCabinetNo("SWAP-C-001");
        cabinet.setSecret("aabbccddeeff00112233445566778899");
        when(cabinetDao.selectOne(any())).thenReturn(cabinet);
        CommandLogEntity cmd = new CommandLogEntity();
        cmd.setId(1L);
        cmd.setCommandSeq(9L);
        when(dispatchService.preparePolicy(anyString(), anyString())).thenReturn(cmd);
        when(policyDao.selectOne(any())).thenReturn(null); // 无历史版本
    }

    private ChargePolicyForm.Window window(int start, int end, int power, int fee) {
        ChargePolicyForm.Window window = new ChargePolicyForm.Window();
        window.setStartHour(start);
        window.setEndHour(end);
        window.setPowerLimitW(power);
        window.setFeeFenPerKwh(fee);
        return window;
    }

    private ChargePolicyForm form(List<ChargePolicyForm.Window> windows) {
        ChargePolicyForm form = new ChargePolicyForm();
        form.setCabinetNo("SWAP-C-001");
        form.setPriority(1);
        form.setWindows(windows);
        return form;
    }

    @Test
    @DisplayName("下发成功：版本=历史+1，落 ACTIVE 并携带 commandSeq")
    void 下发成功() {
        ChargePolicyEntity result = service.apply(form(List.of(
                window(0, 8, 2000, 30), window(8, 22, 400, 150), window(22, 24, 2000, 30))));

        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(1);
        assertThat(result.getCommandSeq()).isEqualTo(9L);
        verify(dispatchService).dispatchPolicy(any(), eq("SWAP-C-001"), eq(1L), any());
    }

    @Test
    @DisplayName("窗口校验：缺口/重叠/越界均拒绝")
    void 窗口校验() {
        assertThatThrownBy(() -> service.apply(form(List.of(window(0, 8, 100, 100)))))
                .isInstanceOf(RRException.class).hasMessageContaining("覆盖到 24");
        assertThatThrownBy(() -> service.apply(form(List.of(
                window(0, 10, 100, 100), window(8, 24, 100, 100)))))
                .isInstanceOf(RRException.class).hasMessageContaining("连续覆盖");
        assertThatThrownBy(() -> service.apply(form(List.of(
                window(0, 8, 20000, 100), window(8, 24, 100, 100)))))
                .isInstanceOf(RRException.class).hasMessageContaining("功率上限");
    }

    @Test
    @DisplayName("下发失败：落 FAILED 行并抛出（可重投）")
    void 下发失败落FAILED() {
        doThrow(new RRException("柜无响应")).when(dispatchService)
                .dispatchPolicy(any(), anyString(), anyLong(), any());

        assertThatThrownBy(() -> service.apply(form(List.of(window(0, 24, 1000, 100)))))
                .isInstanceOf(RRException.class).hasMessageContaining("柜无响应");
        org.mockito.ArgumentCaptor<ChargePolicyEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ChargePolicyEntity.class);
        verify(policyDao).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(2);
    }

    @Test
    @DisplayName("重投：已生效拒绝；失败记录可重投")
    void 重投规则() {
        ChargePolicyEntity active = new ChargePolicyEntity();
        active.setId(1L);
        active.setStatus(1);
        when(policyDao.selectById(1L)).thenReturn(active);
        assertThatThrownBy(() -> service.reapply(1L))
                .isInstanceOf(RRException.class).hasMessageContaining("无需重投");

        ChargePolicyEntity failed = new ChargePolicyEntity();
        failed.setId(2L);
        failed.setStatus(2);
        failed.setVersion(3);
        failed.setPriority(2);
        failed.setCabinetNo("SWAP-C-001");
        failed.setPolicyJson("[{\"startHour\":0,\"endHour\":24,\"powerLimitW\":800,\"feeFenPerKwh\":100}]");
        when(policyDao.selectById(2L)).thenReturn(failed);
        service.reapply(2L);
        verify(dispatchService).dispatchPolicy(any(), eq("SWAP-C-001"), eq(3L), any());
        verify(policyDao, never()).insert(any(ChargePolicyEntity.class)); // 重投不新增版本
    }
}
