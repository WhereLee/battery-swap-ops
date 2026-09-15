package com.swapops.server.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.server.agent.dao.AgentActionDao;
import com.swapops.server.agent.entity.AgentActionEntity;
import com.swapops.server.agent.enums.AgentActionStatus;
import com.swapops.server.agent.enums.AgentActionType;
import com.swapops.server.agent.form.AgentActionForm;
import com.swapops.server.charge.form.ChargePolicyForm;
import com.swapops.server.charge.service.ChargePolicyService;
import com.swapops.server.common.RRException;
import com.swapops.server.common.id.SnowflakeIdGenerator;
import com.swapops.server.common.utils.PageParams;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.reconcile.DailyReconcileTask;
import com.swapops.server.workorder.service.WorkOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 动作接缝（S4.6，两段式）：
 * propose = 只落"建议"（无副作用，幂等键去重）；confirm = 人工确认后 CAS 抢执行权再执行；reject = 关闭。
 * 白名单动作为运维类；资金类不入白名单（文档声明）。执行结果/失败原因全量留痕（审计）。
 */
@Slf4j
@Service
public class AgentActionService {

    private final AgentActionDao actionDao;
    private final WorkOrderService workOrderService;
    private final DailyReconcileTask dailyReconcileTask;
    private final ChargePolicyService chargePolicyService;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentActionService(AgentActionDao actionDao, WorkOrderService workOrderService,
                              DailyReconcileTask dailyReconcileTask,
                              ChargePolicyService chargePolicyService,
                              SnowflakeIdGenerator idGenerator) {
        this.actionDao = actionDao;
        this.workOrderService = workOrderService;
        this.dailyReconcileTask = dailyReconcileTask;
        this.chargePolicyService = chargePolicyService;
        this.idGenerator = idGenerator;
    }

    /** 提交建议（无副作用；同 idemKey 返回既有建议） */
    public AgentActionEntity propose(AgentActionForm form, String idemKey) {
        if (form == null || form.getActionType() == null || form.getActionType().isBlank()) {
            throw new RRException("动作类型必填");
        }
        AgentActionType type = parseType(form.getActionType());
        if (idemKey != null && !idemKey.isBlank()) {
            AgentActionEntity existing = byIdemKey(idemKey);
            if (existing != null) {
                return existing;
            }
        }
        long now = System.currentTimeMillis();
        AgentActionEntity action = new AgentActionEntity();
        action.setActionNo("AA" + idGenerator.nextIdString());
        action.setIdemKey(idemKey == null || idemKey.isBlank() ? null : idemKey.trim());
        action.setActionType(type.name());
        action.setParamsJson(writeJson(form.getParams() == null ? Map.of() : form.getParams()));
        action.setReason(form.getReason());
        action.setStatus(AgentActionStatus.PROPOSED.getCode());
        // S7 WP-A：提出者取管理端身份（此前为客户端可传表单值，可伪造）
        action.setProposer(com.swapops.server.admin.security.AdminContext.currentUsernameOr("agent"));
        action.setCreateTime(now);
        action.setUpdateTime(now);
        try {
            actionDao.insert(action);
        } catch (DuplicateKeyException e) {
            if (action.getIdemKey() != null) {
                return byIdemKey(action.getIdemKey());
            }
            throw e;
        }
        log.info("[agent] 建议单已创建 no={} type={} proposer={}", action.getActionNo(),
                action.getActionType(), action.getProposer());
        return action;
    }

    /** 人工确认：CAS 抢执行权（并发/重复确认只有一次执行）→ 执行 → 落结果或失败 */
    public AgentActionEntity confirm(Long id, String confirmer) {
        AgentActionEntity action = require(id);
        boolean claimed = cas(id, AgentActionStatus.PROPOSED, AgentActionStatus.EXECUTING, wrapper -> wrapper
                .set(AgentActionEntity::getConfirmer, confirmer == null ? "admin" : confirmer)
                .set(AgentActionEntity::getConfirmTime, System.currentTimeMillis()));
        if (!claimed) {
            throw new RRException("建议单当前状态不可确认: "
                    + AgentActionStatus.fromCode(action.getStatus()));
        }
        try {
            Map<String, Object> result = execute(action);
            actionDao.update(null, new LambdaUpdateWrapper<AgentActionEntity>()
                    .eq(AgentActionEntity::getId, id)
                    .eq(AgentActionEntity::getStatus, AgentActionStatus.EXECUTING.getCode())
                    .set(AgentActionEntity::getStatus, AgentActionStatus.EXECUTED.getCode())
                    .set(AgentActionEntity::getResultJson, writeJson(result))
                    .set(AgentActionEntity::getUpdateTime, System.currentTimeMillis()));
            log.info("[agent] 建议单已执行 no={} type={} confirmer={}", action.getActionNo(),
                    action.getActionType(), confirmer);
        } catch (RuntimeException e) {
            log.error("[agent] 建议单执行失败 no={} type={} cause={}", action.getActionNo(),
                    action.getActionType(), e.getMessage());
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            actionDao.update(null, new LambdaUpdateWrapper<AgentActionEntity>()
                    .eq(AgentActionEntity::getId, id)
                    .eq(AgentActionEntity::getStatus, AgentActionStatus.EXECUTING.getCode())
                    .set(AgentActionEntity::getStatus, AgentActionStatus.FAILED.getCode())
                    .set(AgentActionEntity::getErrorMsg, truncate(message))
                    .set(AgentActionEntity::getUpdateTime, System.currentTimeMillis()));
            throw new RRException("建议单执行失败: " + message);
        }
        return actionDao.selectById(id);
    }

    /** 人工驳回（无副作用） */
    public AgentActionEntity reject(Long id, String confirmer, String remark) {
        AgentActionEntity action = require(id);
        boolean moved = cas(id, AgentActionStatus.PROPOSED, AgentActionStatus.REJECTED, wrapper -> wrapper
                .set(AgentActionEntity::getConfirmer, confirmer == null ? "admin" : confirmer)
                .set(AgentActionEntity::getConfirmTime, System.currentTimeMillis())
                .set(AgentActionEntity::getErrorMsg, truncate(remark)));
        if (!moved) {
            throw new RRException("建议单当前状态不可驳回: "
                    + AgentActionStatus.fromCode(action.getStatus()));
        }
        log.info("[agent] 建议单已驳回 no={} confirmer={}", action.getActionNo(), confirmer);
        return actionDao.selectById(id);
    }

    public PageResult<AgentActionEntity> page(Integer page, Integer limit, Integer status) {
        IPage<AgentActionEntity> result = actionDao.selectPage(
                new Page<>(PageParams.page(page), PageParams.limit(limit)),
                new LambdaQueryWrapper<AgentActionEntity>()
                        .eq(status != null, AgentActionEntity::getStatus, status)
                        .orderByDesc(AgentActionEntity::getCreateTime));
        return PageResult.of(result);
    }

    public AgentActionEntity require(Long id) {
        AgentActionEntity action = actionDao.selectById(id);
        if (action == null) {
            throw new RRException("建议单不存在: " + id);
        }
        return action;
    }

    // ---------- 白名单执行（复用既有领域服务；资金类不在其中） ----------

    private Map<String, Object> execute(AgentActionEntity action) {
        AgentActionType type = parseType(action.getActionType());
        Map<String, Object> params = readJson(action.getParamsJson());
        Map<String, Object> result = new LinkedHashMap<>();
        switch (type) {
            case CREATE_WORK_ORDER_FROM_ALARM -> {
                var order = workOrderService.createFromAlarm(requireLong(params, "alarmId"),
                        optionalString(params, "severity"));
                result.put("workOrderId", order.getId());
                result.put("woNo", order.getWoNo());
                result.put("status", order.getStatus());
            }
            case ASSIGN_WORK_ORDER -> {
                var order = workOrderService.assign(requireLong(params, "workOrderId"),
                        requireLong(params, "handlerId"), "agent-confirmed");
                result.put("woNo", order.getWoNo());
                result.put("status", order.getStatus());
            }
            case RUN_RECONCILE -> {
                Map<String, Object> report = dailyReconcileTask.runAndStore();
                result.put("totalViolations", report.get("totalViolations"));
                result.put("runAt", report.get("runAt"));
            }
            case APPLY_CHARGE_POLICY -> {
                ChargePolicyForm form = new ChargePolicyForm();
                form.setCabinetNo(requireString(params, "cabinetNo"));
                Object priority = params.get("priority");
                form.setPriority(priority == null ? 2 : Integer.parseInt(String.valueOf(priority)));
                form.setWindows(objectMapper.convertValue(params.get("windows"),
                        objectMapper.getTypeFactory().constructCollectionType(java.util.List.class,
                                ChargePolicyForm.Window.class)));
                var policy = chargePolicyService.apply(form);
                result.put("cabinetNo", policy.getCabinetNo());
                result.put("version", policy.getVersion());
                result.put("status", policy.getStatus());
            }
        }
        return result;
    }

    private AgentActionType parseType(String raw) {
        try {
            return AgentActionType.valueOf(raw == null ? "" : raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RRException("动作类型不在白名单: " + raw);
        }
    }

    private AgentActionEntity byIdemKey(String idemKey) {
        return actionDao.selectOne(new LambdaQueryWrapper<AgentActionEntity>()
                .eq(AgentActionEntity::getIdemKey, idemKey));
    }

    private boolean cas(Long id, AgentActionStatus from, AgentActionStatus to,
                        java.util.function.Consumer<LambdaUpdateWrapper<AgentActionEntity>> extra) {
        LambdaUpdateWrapper<AgentActionEntity> wrapper = new LambdaUpdateWrapper<AgentActionEntity>()
                .eq(AgentActionEntity::getId, id)
                .eq(AgentActionEntity::getStatus, from.getCode())
                .set(AgentActionEntity::getStatus, to.getCode())
                .set(AgentActionEntity::getUpdateTime, System.currentTimeMillis());
        extra.accept(wrapper);
        return actionDao.update(null, wrapper) > 0;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        try {
            return json == null || json.isBlank() ? Map.of()
                    : objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("参数反序列化失败", e);
        }
    }

    private Long requireLong(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            throw new RRException("缺参数: " + key);
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new RRException("参数非法(需数字): " + key + "=" + value);
        }
    }

    private String requireString(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new RRException("缺参数: " + key);
        }
        return String.valueOf(value);
    }

    private String optionalString(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 255 ? message : message.substring(0, 255);
    }
}
