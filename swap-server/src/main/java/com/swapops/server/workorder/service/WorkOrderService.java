package com.swapops.server.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.dao.AlarmDao;
import com.swapops.server.alarm.entity.AlarmEntity;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.common.RRException;
import com.swapops.server.common.delay.DelayQueueService;
import com.swapops.server.common.id.SnowflakeIdGenerator;
import com.swapops.server.common.utils.PageParams;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.workorder.config.WorkOrderProperties;
import com.swapops.server.workorder.dao.WorkOrderDao;
import com.swapops.server.workorder.dao.WorkOrderLogDao;
import com.swapops.server.workorder.entity.WorkOrderEntity;
import com.swapops.server.workorder.entity.WorkOrderLogEntity;
import com.swapops.server.workorder.enums.WorkOrderSeverity;
import com.swapops.server.workorder.enums.WorkOrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单闭环（S4.4）：告警→创建→分诊→派单→处置→验收→关闭；全 CAS 只前向，流转留痕。
 * SLA：创建时按严重级算截止，经延迟队列到点检查；超时置位 + WORK_ORDER_SLA_BREACH 告警。
 */
@Slf4j
@Service
public class WorkOrderService {

    /** SLA 延迟任务主题 */
    public static final String SLA_TOPIC = "work-order-sla";

    private final WorkOrderDao workOrderDao;
    private final WorkOrderLogDao workOrderLogDao;
    private final AlarmDao alarmDao;
    private final AlarmService alarmService;
    private final DelayQueueService delayQueueService;
    private final SnowflakeIdGenerator idGenerator;
    private final WorkOrderProperties properties;

    public WorkOrderService(WorkOrderDao workOrderDao, WorkOrderLogDao workOrderLogDao, AlarmDao alarmDao,
                            AlarmService alarmService, DelayQueueService delayQueueService,
                            SnowflakeIdGenerator idGenerator, WorkOrderProperties properties) {
        this.workOrderDao = workOrderDao;
        this.workOrderLogDao = workOrderLogDao;
        this.alarmDao = alarmDao;
        this.alarmService = alarmService;
        this.delayQueueService = delayQueueService;
        this.idGenerator = idGenerator;
        this.properties = properties;
    }

    /** 告警转工单（alarm_id 唯一：重复转换幂等返回既有工单） */
    @Transactional
    public WorkOrderEntity createFromAlarm(Long alarmId, String severity) {
        AlarmEntity alarm = alarmDao.selectById(alarmId);
        if (alarm == null) {
            throw new RRException("告警不存在: " + alarmId);
        }
        WorkOrderEntity existing = byAlarmId(alarmId);
        if (existing != null) {
            return existing;
        }
        String level = severity == null || severity.isBlank()
                ? defaultSeverity(alarm.getAlarmType()) : severity.trim().toUpperCase();
        parseSeverity(level);
        long now = System.currentTimeMillis();
        WorkOrderEntity order = new WorkOrderEntity();
        order.setWoNo("WO" + idGenerator.nextIdString());
        order.setAlarmId(alarmId);
        order.setDeviceType(alarm.getDeviceType());
        order.setDeviceNo(alarm.getDeviceNo());
        order.setTitle("[" + alarm.getAlarmType() + "] " + alarm.getDeviceNo());
        order.setSeverity(level);
        order.setStatus(WorkOrderStatus.OPEN.getCode());
        order.setSlaDeadline(now + properties.slaMinutes(level) * 60_000L);
        order.setSlaBreached(0);
        order.setCreateTime(now);
        order.setUpdateTime(now);
        try {
            workOrderDao.insert(order);
        } catch (DuplicateKeyException e) {
            return byAlarmId(alarmId); // 并发重复转换
        }
        writeLog(order.getWoNo(), "CREATE", null, WorkOrderStatus.OPEN, "system",
                "来源告警:" + alarmId);
        enqueueSla(order.getWoNo(), order.getSlaDeadline());
        log.info("[工单] 创建 woNo={} alarmId={} severity={} slaDeadline={}",
                order.getWoNo(), alarmId, level, order.getSlaDeadline());
        return order;
    }

    /** 分诊：OPEN→TRIAGED（可升级/降级严重级） */
    public WorkOrderEntity triage(Long id, String severity, String remark) {
        WorkOrderEntity order = require(id);
        if (severity != null && !severity.isBlank()) {
            parseSeverity(severity.trim().toUpperCase());
        }
        boolean moved = cas(order, WorkOrderStatus.OPEN, WorkOrderStatus.TRIAGED, wrapper -> {
            if (severity != null && !severity.isBlank()) {
                wrapper.set(WorkOrderEntity::getSeverity, severity.trim().toUpperCase());
            }
            wrapper.set(WorkOrderEntity::getRemark, remark);
        });
        return afterMove(order, moved, "TRIAGE", WorkOrderStatus.TRIAGED, remark);
    }

    /** 派单：TRIAGED→ASSIGNED */
    public WorkOrderEntity assign(Long id, Long handlerId, String remark) {
        WorkOrderEntity order = require(id);
        boolean moved = cas(order, WorkOrderStatus.TRIAGED, WorkOrderStatus.ASSIGNED, wrapper -> wrapper
                .set(WorkOrderEntity::getHandlerId, handlerId)
                .set(WorkOrderEntity::getRemark, remark));
        return afterMove(order, moved, "ASSIGN", WorkOrderStatus.ASSIGNED, remark);
    }

    /** 开始处置：ASSIGNED→HANDLING */
    public WorkOrderEntity start(Long id, String remark) {
        WorkOrderEntity order = require(id);
        boolean moved = cas(order, WorkOrderStatus.ASSIGNED, WorkOrderStatus.HANDLING,
                wrapper -> wrapper.set(WorkOrderEntity::getRemark, remark));
        return afterMove(order, moved, "START", WorkOrderStatus.HANDLING, remark);
    }

    /** 验收：HANDLING→VERIFIED（撤销 SLA 定时） */
    public WorkOrderEntity verify(Long id, String remark) {
        WorkOrderEntity order = require(id);
        boolean moved = cas(order, WorkOrderStatus.HANDLING, WorkOrderStatus.VERIFIED, wrapper -> wrapper
                .set(WorkOrderEntity::getVerifyTime, System.currentTimeMillis())
                .set(WorkOrderEntity::getRemark, remark));
        if (moved) {
            cancelSla(order.getWoNo());
        }
        return afterMove(order, moved, "VERIFY", WorkOrderStatus.VERIFIED, remark);
    }

    /** 关闭：VERIFIED→CLOSED（撤销 SLA 定时） */
    public WorkOrderEntity close(Long id, String remark) {
        WorkOrderEntity order = require(id);
        boolean moved = cas(order, WorkOrderStatus.VERIFIED, WorkOrderStatus.CLOSED, wrapper -> wrapper
                .set(WorkOrderEntity::getCloseTime, System.currentTimeMillis())
                .set(WorkOrderEntity::getRemark, remark));
        if (moved) {
            cancelSla(order.getWoNo());
        }
        return afterMove(order, moved, "CLOSE", WorkOrderStatus.CLOSED, remark);
    }

    /** SLA 到点：未验收/关闭则置超时位并告警（幂等：已置位/终态直接返回 false） */
    public boolean markSlaBreached(String woNo) {
        WorkOrderEntity order = byWoNo(woNo);
        if (order == null || order.getStatus() >= WorkOrderStatus.VERIFIED.getCode()) {
            return false;
        }
        int rows = workOrderDao.update(null, new LambdaUpdateWrapper<WorkOrderEntity>()
                .eq(WorkOrderEntity::getId, order.getId())
                .in(WorkOrderEntity::getStatus, List.of(WorkOrderStatus.OPEN.getCode(),
                        WorkOrderStatus.TRIAGED.getCode(), WorkOrderStatus.ASSIGNED.getCode(),
                        WorkOrderStatus.HANDLING.getCode()))
                .eq(WorkOrderEntity::getSlaBreached, 0)
                .set(WorkOrderEntity::getSlaBreached, 1)
                .set(WorkOrderEntity::getUpdateTime, System.currentTimeMillis()));
        if (rows == 0) {
            return false;
        }
        writeLog(woNo, "SLA_BREACH", WorkOrderStatus.fromCode(order.getStatus()), WorkOrderStatus.fromCode(order.getStatus()), "system",
                "SLA 超时 deadline=" + order.getSlaDeadline());
        alarmService.raise(AlarmService.DEVICE_SYSTEM, woNo, AlarmType.WORK_ORDER_SLA_BREACH,
                "工单 SLA 超时 severity=" + order.getSeverity() + " 设备=" + order.getDeviceNo());
        log.error("[工单] SLA 超时 woNo={} severity={} deadline={}",
                woNo, order.getSeverity(), order.getSlaDeadline());
        return true;
    }

    public WorkOrderEntity require(Long id) {
        WorkOrderEntity order = workOrderDao.selectById(id);
        if (order == null) {
            throw new RRException("工单不存在: " + id);
        }
        return order;
    }

    public WorkOrderEntity byWoNo(String woNo) {
        return workOrderDao.selectOne(new LambdaQueryWrapper<WorkOrderEntity>()
                .eq(WorkOrderEntity::getWoNo, woNo));
    }

    public List<WorkOrderLogEntity> logs(String woNo) {
        return workOrderLogDao.selectList(new LambdaQueryWrapper<WorkOrderLogEntity>()
                .eq(WorkOrderLogEntity::getWoNo, woNo)
                .orderByAsc(WorkOrderLogEntity::getId));
    }

    public PageResult<WorkOrderEntity> page(Integer page, Integer limit, Integer status) {
        IPage<WorkOrderEntity> result = workOrderDao.selectPage(
                new Page<>(PageParams.page(page), PageParams.limit(limit)),
                new LambdaQueryWrapper<WorkOrderEntity>()
                        .eq(status != null, WorkOrderEntity::getStatus, status)
                        .orderByDesc(WorkOrderEntity::getCreateTime));
        return PageResult.of(result);
    }

    public Map<String, Object> detail(Long id) {
        WorkOrderEntity order = require(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("order", order);
        view.put("logs", logs(order.getWoNo()));
        return view;
    }

    // ---------- 内部 ----------

    private WorkOrderEntity afterMove(WorkOrderEntity order, boolean moved, String action,
                                      WorkOrderStatus to, String remark) {
        if (!moved) {
            throw new RRException("工单状态不允许该操作: 当前 "
                    + WorkOrderStatus.fromCode(order.getStatus()) + " 期望前置 " + action);
        }
        writeLog(order.getWoNo(), action, WorkOrderStatus.fromCode(order.getStatus()), to, "admin", remark);
        return workOrderDao.selectById(order.getId());
    }

    private boolean cas(WorkOrderEntity order, WorkOrderStatus from, WorkOrderStatus to,
                        java.util.function.Consumer<LambdaUpdateWrapper<WorkOrderEntity>> extra) {
        LambdaUpdateWrapper<WorkOrderEntity> wrapper = new LambdaUpdateWrapper<WorkOrderEntity>()
                .eq(WorkOrderEntity::getId, order.getId())
                .eq(WorkOrderEntity::getStatus, from.getCode())
                .set(WorkOrderEntity::getStatus, to.getCode())
                .set(WorkOrderEntity::getUpdateTime, System.currentTimeMillis());
        extra.accept(wrapper);
        return workOrderDao.update(null, wrapper) > 0;
    }

    private void writeLog(String woNo, String action, WorkOrderStatus from, WorkOrderStatus to,
                          String operator, String remark) {
        WorkOrderLogEntity entity = new WorkOrderLogEntity();
        entity.setWoNo(woNo);
        entity.setAction(action);
        entity.setFromStatus(from == null ? null : from.getCode());
        entity.setToStatus(to == null ? null : to.getCode());
        entity.setOperator(operator);
        entity.setRemark(remark);
        entity.setCreateTime(System.currentTimeMillis());
        workOrderLogDao.insert(entity);
    }

    private WorkOrderEntity byAlarmId(Long alarmId) {
        return workOrderDao.selectOne(new LambdaQueryWrapper<WorkOrderEntity>()
                .eq(WorkOrderEntity::getAlarmId, alarmId));
    }

    private WorkOrderSeverity parseSeverity(String severity) {
        try {
            return WorkOrderSeverity.valueOf(severity);
        } catch (IllegalArgumentException e) {
            throw new RRException("严重级非法（HIGH/MEDIUM/LOW）: " + severity);
        }
    }

    private String defaultSeverity(String alarmType) {
        if (alarmType == null) {
            return WorkOrderSeverity.MEDIUM.name();
        }
        return switch (alarmType) {
            case "CABINET_FAULT", "RECONCILE_ERROR", "OUTBOX_DEAD", "WORK_ORDER_SLA_BREACH" ->
                    WorkOrderSeverity.HIGH.name();
            case "OFFLINE", "BATCH_OFFLINE", "CELL_FAULT" -> WorkOrderSeverity.MEDIUM.name();
            default -> WorkOrderSeverity.LOW.name();
        };
    }

    private void enqueueSla(String woNo, long deadline) {
        try {
            delayQueueService.enqueue(SLA_TOPIC, woNo, "{\"woNo\":\"" + woNo + "\"}", deadline);
        } catch (RuntimeException e) {
            log.warn("[工单] SLA 定时登记失败（扫描兜底缺失，需人工关注） woNo={} cause={}", woNo, e.getMessage());
        }
    }

    private void cancelSla(String woNo) {
        try {
            delayQueueService.cancel(SLA_TOPIC, woNo);
        } catch (RuntimeException e) {
            log.warn("[工单] SLA 定时取消失败（终态判定保证空转无害） woNo={} cause={}", woNo, e.getMessage());
        }
    }
}
