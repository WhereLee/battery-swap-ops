package com.swapops.server.web.view.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.server.admin.security.AdminContext;
import com.swapops.server.agent.dao.AgentActionDao;
import com.swapops.server.agent.entity.AgentActionEntity;
import com.swapops.server.alarm.dao.AlarmDao;
import com.swapops.server.alarm.entity.AlarmEntity;
import com.swapops.server.common.action.ActionsSupport;
import com.swapops.server.common.utils.PageParams;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.dashboard.service.DashboardService;
import com.swapops.server.web.view.AdminViews;
import com.swapops.server.workorder.service.WorkOrderService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 观测口视图（S8）：看板 / 告警页 / 建议单页——前端"看得见、点得动"所需的读聚合。
 *
 * <p>刻意只做三件事：取数、拼装、给出 {@code allowedActions}；<b>不含任何业务写入与规则判断</b>
 * （规则在各域 service 与 {@link ActionsSupport}）。批量查询防 N+1（一页告警的工单映射、
 * 一页建议单的来源告警各一次 in 查询）。
 */
@Service
public class AdminObservationViewService {

    private final DashboardService dashboardService;
    private final AlarmDao alarmDao;
    private final AgentActionDao agentActionDao;
    private final WorkOrderService workOrderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminObservationViewService(DashboardService dashboardService, AlarmDao alarmDao,
                                       AgentActionDao agentActionDao, WorkOrderService workOrderService) {
        this.dashboardService = dashboardService;
        this.alarmDao = alarmDao;
        this.agentActionDao = agentActionDao;
        this.workOrderService = workOrderService;
    }

    /** 看板视图：Map 口径转强类型，并如实标出本次是否"本域口径"（受限身份绕全局缓存直算）。 */
    public AdminViews.DashboardVO dashboard() {
        Map<String, Object> raw = dashboardService.overview();
        AdminContext.Principal principal = AdminContext.current();
        return new AdminViews.DashboardVO(number(raw.get("generatedAt")).longValue(),
                number(raw.get("totalBatteries")).longValue(), number(raw.get("fullBatteries")).longValue(),
                number(raw.get("fullBatteryRate")).doubleValue(), number(raw.get("totalStations")).longValue(),
                number(raw.get("availableStations")).longValue(),
                number(raw.get("stationAvailabilityRate")).doubleValue(),
                number(raw.get("completedToday")).longValue(), number(raw.get("turnoverRate")).doubleValue(),
                asStringList(raw.get("unavailableStations")),
                principal != null && principal.dataScoped());
    }

    /**
     * 告警分页视图。动作口径：未处理 → 可人工处置；<b>尚未转工单</b>时才追加 {@code create-work-order}
     * （跨资源可用性必须看查询结果，状态机纯函数给不了——这正是 §4.4 里"跨资源判断留在视图层"的边界）。
     */
    public PageResult<AdminViews.AlarmItemVO> alarmPage(Integer page, Integer limit, Integer handled) {
        IPage<AlarmEntity> result = alarmDao.selectPage(
                new Page<>(PageParams.page(page), PageParams.limit(limit)),
                new LambdaQueryWrapper<AlarmEntity>()
                        .eq(handled != null, AlarmEntity::getHandled, handled)
                        .orderByDesc(AlarmEntity::getCreateTime));
        List<AlarmEntity> records = result.getRecords();
        List<Long> ids = records.stream().map(AlarmEntity::getId).toList();
        Map<Long, Long> workOrders = workOrderService.workOrderIdsByAlarmIds(ids);

        List<AdminViews.AlarmItemVO> views = new ArrayList<>(records.size());
        for (AlarmEntity alarm : records) {
            Long workOrderId = workOrders.get(alarm.getId());
            List<String> actions = new ArrayList<>(ActionsSupport.alarm(alarm.getHandled()));
            if (actions.contains("handle") && workOrderId == null) {
                actions.add("create-work-order");
            }
            views.add(new AdminViews.AlarmItemVO(alarm.getId(), alarm.getDeviceType(), alarm.getDeviceNo(),
                    alarm.getAlarmType(), alarm.getContent(), alarm.getHandled(), alarm.getHandler(),
                    alarm.getCreateTime(), alarm.getHandledTime(), workOrderId, List.copyOf(actions)));
        }
        return PageResult.of(views, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 建议单分页视图（S6 闭环的人工处置入口）：带出来源告警，前端无需再查告警接口。 */
    public PageResult<AdminViews.SuggestionVO> agentActionPage(Integer page, Integer limit, Integer status) {
        IPage<AgentActionEntity> result = agentActionDao.selectPage(
                new Page<>(PageParams.page(page), PageParams.limit(limit)),
                new LambdaQueryWrapper<AgentActionEntity>()
                        .eq(status != null, AgentActionEntity::getStatus, status)
                        .orderByDesc(AgentActionEntity::getCreateTime));
        List<AgentActionEntity> records = result.getRecords();

        Map<Long, Long> actionAlarmIds = new LinkedHashMap<>();
        for (AgentActionEntity action : records) {
            Long alarmId = parseAlarmId(action.getParamsJson());
            if (alarmId != null) {
                actionAlarmIds.put(action.getId(), alarmId);
            }
        }
        Map<Long, AlarmEntity> alarms = new LinkedHashMap<>();
        if (!actionAlarmIds.isEmpty()) {
            for (AlarmEntity alarm : alarmDao.selectBatchIds(new java.util.HashSet<>(actionAlarmIds.values()))) {
                alarms.put(alarm.getId(), alarm);
            }
        }

        List<AdminViews.SuggestionVO> views = new ArrayList<>(records.size());
        for (AgentActionEntity action : records) {
            Long alarmId = actionAlarmIds.get(action.getId());
            AlarmEntity alarm = alarmId == null ? null : alarms.get(alarmId);
            views.add(new AdminViews.SuggestionVO(action.getId(), action.getActionNo(), action.getActionType(),
                    action.getIdemKey(), action.getReason(), action.getStatus(), action.getProposer(),
                    action.getConfirmer(), action.getConfirmTime(), action.getResultJson(), action.getErrorMsg(),
                    action.getCreateTime(), alarmId, alarm == null ? null : alarm.getAlarmType(),
                    ActionsSupport.agentAction(action.getStatus())));
        }
        return PageResult.of(views, result.getTotal(), result.getCurrent(), result.getSize());
    }

    // ---------- 内部 ----------

    /** params_json 解析来源告警 id；解析失败不影响列表（该列留 null，不抛）。 */
    private Long parseAlarmId(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(paramsJson).path("alarmId");
            return node.isNumber() ? node.asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Number number(Object value) {
        return value instanceof Number number ? number : 0;
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }
}
