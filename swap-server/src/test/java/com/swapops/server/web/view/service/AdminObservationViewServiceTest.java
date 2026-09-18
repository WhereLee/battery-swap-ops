package com.swapops.server.web.view.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import com.swapops.server.agent.dao.AgentActionDao;
import com.swapops.server.agent.entity.AgentActionEntity;
import com.swapops.server.alarm.dao.AlarmDao;
import com.swapops.server.alarm.entity.AlarmEntity;
import com.swapops.server.dashboard.service.DashboardService;
import com.swapops.server.web.view.AdminViews;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.workorder.service.WorkOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 观测口视图测试：Map→VO 映射、动作码口径（跨资源判断）、批量带出来源告警。 */
@DisplayName("观测口视图（看板/告警页/建议单页）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminObservationViewServiceTest {

    @Mock
    private DashboardService dashboardService;
    @Mock
    private AlarmDao alarmDao;
    @Mock
    private AgentActionDao agentActionDao;
    @Mock
    private WorkOrderService workOrderService;

    private AdminObservationViewService service;

    @BeforeEach
    void setUp() {
        service = new AdminObservationViewService(dashboardService, alarmDao, agentActionDao, workOrderService);
    }

    @AfterEach
    void tearDown() {
        AdminContext.set(null);
    }

    private AlarmEntity alarm(long id, int handled) {
        AlarmEntity alarm = new AlarmEntity();
        alarm.setId(id);
        alarm.setDeviceType("CABINET");
        alarm.setDeviceNo("SWAP-C-005");
        alarm.setAlarmType("CABINET_FAULT");
        alarm.setContent("柜级故障");
        alarm.setHandled(handled);
        alarm.setCreateTime(1700000000000L);
        return alarm;
    }

    @SuppressWarnings("unchecked")
    private <T> Page<T> pageOf(T... records) {
        Page<T> page = new Page<>(1, 20);
        page.setRecords(List.of(records));
        page.setTotal(records.length);
        return page;
    }

    @Test
    @DisplayName("看板：Map 口径转强类型 VO，未受限身份 dataScoped=false")
    void 看板映射() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("generatedAt", 1700000000000L);
        raw.put("totalBatteries", 40L);
        raw.put("fullBatteries", 30L);
        raw.put("fullBatteryRate", 0.75);
        raw.put("totalStations", 2L);
        raw.put("availableStations", 2L);
        raw.put("stationAvailabilityRate", 1.0);
        raw.put("completedToday", 12L);
        raw.put("turnoverRate", 0.3);
        raw.put("unavailableStations", List.of());
        when(dashboardService.overview()).thenReturn(raw);

        AdminViews.DashboardVO vo = service.dashboard();

        assertThat(vo.totalBatteries()).isEqualTo(40L);
        assertThat(vo.fullBatteryRate()).isEqualTo(0.75);
        assertThat(vo.dataScoped()).isFalse();
    }

    @Test
    @DisplayName("看板：受限身份如实标记 dataScoped=true（本域口径，不冒充全局）")
    void 看板标记本域口径() {
        AdminContext.set(new AdminContext.Principal(9L, "ops01", AdminRole.OPS, false, "STATION", Set.of(3L)));
        when(dashboardService.overview()).thenReturn(new LinkedHashMap<>());

        assertThat(service.dashboard().dataScoped()).isTrue();
        assertThat(service.dashboard().totalBatteries()).isZero(); // 缺键按 0 兜底，不抛 NPE
    }

    @Test
    @DisplayName("告警页：未处理且尚无工单 → handle + create-work-order")
    void 告警页给出建单动作() {
        when(alarmDao.selectPage(any(), any())).thenReturn(pageOf(alarm(1L, 0)));
        when(workOrderService.workOrderIdsByAlarmIds(any())).thenReturn(Map.of());

        PageResult<AdminViews.AlarmItemVO> result = service.alarmPage(1, 20, null);

        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).allowedActions()).containsExactly("handle", "create-work-order");
        assertThat(result.getList().get(0).workOrderId()).isNull();
    }

    @Test
    @DisplayName("告警页：已有工单则不再给建单动作（防重复建单）；已处理无动作")
    void 告警页已有工单不重复建单() {
        when(alarmDao.selectPage(any(), any())).thenReturn(pageOf(alarm(1L, 0), alarm(2L, 1)));
        when(workOrderService.workOrderIdsByAlarmIds(any())).thenReturn(Map.of(1L, 77L));

        List<AdminViews.AlarmItemVO> list = service.alarmPage(1, 20, null).getList();

        assertThat(list.get(0).workOrderId()).isEqualTo(77L);
        assertThat(list.get(0).allowedActions()).containsExactly("handle");
        assertThat(list.get(1).allowedActions()).isEmpty();
    }

    @Test
    @DisplayName("建议单页：解析 params_json 带出来源告警类型，PROPOSED 给 confirm/reject")
    void 建议单页带出来源告警() {
        AgentActionEntity action = new AgentActionEntity();
        action.setId(5L);
        action.setActionNo("AA1");
        action.setActionType("CREATE_WORK_ORDER_FROM_ALARM");
        action.setIdemKey("agent-3941-create_work_order_from_alarm");
        action.setReason("[agent-auto] 柜级故障 SWAP-C-031");
        action.setStatus(1);
        action.setProposer("swap-agent");
        action.setParamsJson("{\"alarmId\":3941,\"severity\":\"HIGH\"}");
        when(agentActionDao.selectPage(any(), any())).thenReturn(pageOf(action));
        when(alarmDao.selectBatchIds(any())).thenReturn(List.of(alarm(3941L, 0)));

        AdminViews.SuggestionVO vo = service.agentActionPage(1, 20, null).getList().get(0);

        assertThat(vo.alarmId()).isEqualTo(3941L);
        assertThat(vo.alarmType()).isEqualTo("CABINET_FAULT");
        assertThat(vo.allowedActions()).containsExactly("confirm", "reject");
    }

    @Test
    @DisplayName("建议单页：params_json 非法不炸列表（该列留 null）")
    void 建议单页脏参数容忍() {
        AgentActionEntity action = new AgentActionEntity();
        action.setId(6L);
        action.setActionType("RUN_RECONCILE");
        action.setStatus(2);
        action.setParamsJson("not-json");
        when(agentActionDao.selectPage(any(), any())).thenReturn(pageOf(action));

        AdminViews.SuggestionVO vo = service.agentActionPage(1, 20, null).getList().get(0);

        assertThat(vo.alarmId()).isNull();
        assertThat(vo.alarmType()).isNull();
        assertThat(vo.allowedActions()).isEmpty(); // EXECUTED 终态无动作
    }
}
