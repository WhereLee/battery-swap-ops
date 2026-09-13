package com.swapops.server.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.agent.dao.AgentActionDao;
import com.swapops.server.agent.entity.AgentActionEntity;
import com.swapops.server.agent.enums.AgentActionStatus;
import com.swapops.server.agent.form.AgentActionForm;
import com.swapops.server.charge.service.ChargePolicyService;
import com.swapops.server.common.RRException;
import com.swapops.server.common.id.SnowflakeIdGenerator;
import com.swapops.server.reconcile.DailyReconcileTask;
import com.swapops.server.workorder.entity.WorkOrderEntity;
import com.swapops.server.workorder.service.WorkOrderService;
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
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 接缝单测（S4.6）：建议幂等/白名单、确认 CAS 抢执行权、执行成功落 EXECUTED、失败落 FAILED、驳回规则。
 */
@DisplayName("Agent 动作接缝（两段式）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentActionServiceTest {

    @Mock
    private AgentActionDao actionDao;
    @Mock
    private WorkOrderService workOrderService;
    @Mock
    private DailyReconcileTask dailyReconcileTask;
    @Mock
    private ChargePolicyService chargePolicyService;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    private AgentActionService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentActionEntity.class);
    }

    @BeforeEach
    void setUp() {
        when(idGenerator.nextIdString()).thenReturn("123");
        service = new AgentActionService(actionDao, workOrderService, dailyReconcileTask,
                chargePolicyService, idGenerator);
    }

    private AgentActionEntity action(int status, String type, String params) {
        AgentActionEntity action = new AgentActionEntity();
        action.setId(1L);
        action.setActionNo("AA123");
        action.setActionType(type);
        action.setParamsJson(params);
        action.setStatus(status);
        return action;
    }

    private AgentActionForm form(String type, Map<String, Object> params) {
        AgentActionForm form = new AgentActionForm();
        form.setActionType(type);
        form.setParams(params);
        form.setProposer("agent-ops");
        form.setReason("health alarm detected");
        return form;
    }

    @Test
    @DisplayName("建议：落 PROPOSED 无副作用；同 idemKey 幂等返回既有")
    void 建议幂等() {
        when(actionDao.insert(any(AgentActionEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, AgentActionEntity.class).setId(1L);
            return 1;
        });
        AgentActionEntity created = service.propose(form("CREATE_WORK_ORDER_FROM_ALARM",
                Map.of("alarmId", 9)), "idem-1");
        assertThat(created.getStatus()).isEqualTo(AgentActionStatus.PROPOSED.getCode());
        verify(workOrderService, never()).createFromAlarm(anyLong(), any());

        when(actionDao.selectOne(any())).thenReturn(created);
        AgentActionEntity replay = service.propose(form("CREATE_WORK_ORDER_FROM_ALARM",
                Map.of("alarmId", 9)), "idem-1");
        assertThat(replay.getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("并发插入冲突：幂等键兜底返回既有")
    void 并发冲突重读() {
        when(actionDao.insert(any(AgentActionEntity.class))).thenThrow(new DuplicateKeyException("uk"));
        when(actionDao.selectOne(any())).thenReturn(action(AgentActionStatus.PROPOSED.getCode(),
                "RUN_RECONCILE", "{}"));

        AgentActionEntity result = service.propose(form("RUN_RECONCILE", Map.of()), "idem-2");

        assertThat(result.getActionNo()).isEqualTo("AA123");
    }

    @Test
    @DisplayName("白名单：未开放动作拒绝")
    void 白名单拒绝() {
        assertThatThrownBy(() -> service.propose(form("REFUND_MONEY", Map.of()), null))
                .isInstanceOf(RRException.class).hasMessageContaining("不在白名单");
    }

    @Test
    @DisplayName("确认：CAS 抢执行权 → 执行 → EXECUTED + 结果留痕")
    void 确认执行() {
        when(actionDao.selectById(1L)).thenReturn(action(AgentActionStatus.PROPOSED.getCode(),
                "CREATE_WORK_ORDER_FROM_ALARM", "{\"alarmId\":9}"));
        when(actionDao.update(isNullW(), any())).thenReturn(1);
        WorkOrderEntity order = new WorkOrderEntity();
        order.setId(5L);
        order.setWoNo("WO5");
        order.setStatus(1);
        when(workOrderService.createFromAlarm(9L, null)).thenReturn(order);

        service.confirm(1L, "admin");

        verify(workOrderService).createFromAlarm(9L, null);
        verify(actionDao, org.mockito.Mockito.atLeastOnce()).update(isNullW(), any());
    }

    @Test
    @DisplayName("确认：CAS 未命中（已处理）拒绝，不重复执行")
    void 重复确认拒绝() {
        when(actionDao.selectById(1L)).thenReturn(action(AgentActionStatus.EXECUTED.getCode(),
                "RUN_RECONCILE", "{}"));
        when(actionDao.update(isNullW(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(1L, "admin"))
                .isInstanceOf(RRException.class).hasMessageContaining("不可确认");
        verify(dailyReconcileTask, never()).runAndStore();
    }

    @Test
    @DisplayName("执行失败：落 FAILED + 错误留痕并抛出")
    void 执行失败落FAILED() {
        when(actionDao.selectById(1L)).thenReturn(action(AgentActionStatus.PROPOSED.getCode(),
                "CREATE_WORK_ORDER_FROM_ALARM", "{\"alarmId\":9}"));
        when(actionDao.update(isNullW(), any())).thenReturn(1);
        when(workOrderService.createFromAlarm(anyLong(), any()))
                .thenThrow(new RRException("告警不存在"));

        assertThatThrownBy(() -> service.confirm(1L, "admin"))
                .isInstanceOf(RRException.class).hasMessageContaining("执行失败");
        verify(actionDao, org.mockito.Mockito.times(2)).update(isNullW(), any()); // 抢权 + 落 FAILED
    }

    @Test
    @DisplayName("驳回：仅 PROPOSED 可驳回")
    void 驳回规则() {
        when(actionDao.selectById(1L)).thenReturn(action(AgentActionStatus.PROPOSED.getCode(),
                "RUN_RECONCILE", "{}"));
        when(actionDao.update(isNullW(), any())).thenReturn(1);
        service.reject(1L, "admin", "not needed");
        verify(actionDao).update(isNullW(), any());

        when(actionDao.selectById(1L)).thenReturn(action(AgentActionStatus.EXECUTED.getCode(),
                "RUN_RECONCILE", "{}"));
        when(actionDao.update(isNullW(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.reject(1L, "admin", "x"))
                .isInstanceOf(RRException.class).hasMessageContaining("不可驳回");
    }

    private static <T> T isNullW() {
        return org.mockito.ArgumentMatchers.isNull();
    }
}
