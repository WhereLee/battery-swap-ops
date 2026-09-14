package com.swapops.server.charge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.server.charge.dao.ChargePolicyDao;
import com.swapops.server.charge.entity.ChargePolicyEntity;
import com.swapops.server.charge.form.ChargePolicyForm;
import com.swapops.server.common.RRException;
import com.swapops.server.common.filter.TraceIdFilter;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import com.swapops.server.device.service.CommandDispatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 充电策略（S4.3）：窗口校验（连续覆盖 0~24、不重叠）→ 版本+1 → 下发柜侧 → 落版本历史。
 * 下发失败落 FAILED（可 reapply；柜侧版本单调，同版本重投=幂等）。
 */
@Slf4j
@Service
public class ChargePolicyService {

    private final ChargePolicyDao policyDao;
    private final CabinetDao cabinetDao;
    private final CommandDispatchService dispatchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChargePolicyService(ChargePolicyDao policyDao, CabinetDao cabinetDao,
                               CommandDispatchService dispatchService) {
        this.policyDao = policyDao;
        this.cabinetDao = cabinetDao;
        this.dispatchService = dispatchService;
    }

    public ChargePolicyEntity apply(ChargePolicyForm form) {
        if (form == null || form.getCabinetNo() == null || form.getCabinetNo().isBlank()) {
            throw new RRException("柜编号必填");
        }
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, form.getCabinetNo()));
        if (cabinet == null) {
            throw new RRException("柜不存在: " + form.getCabinetNo());
        }
        List<ChargePolicyForm.Window> windows = validateWindows(form.getWindows());
        int priority = validatePriority(form.getPriority());
        int version = latestVersion(form.getCabinetNo()) + 1;
        String windowsJson = writeJson(windows);
        Map<String, Object> policy = buildPolicy(version, priority, windows);

        CommandLogEntity cmdLog = dispatchService.preparePolicy(form.getCabinetNo(),
                TraceIdFilter.currentOrGenerate());
        try {
            dispatchService.dispatchPolicy(cmdLog, form.getCabinetNo(), version, policy);
        } catch (RRException e) {
            save(form.getCabinetNo(), version, windowsJson, priority, 2, cmdLog.getCommandSeq(), e.getMessage());
            throw e;
        }
        ChargePolicyEntity saved = save(form.getCabinetNo(), version, windowsJson, priority, 1,
                cmdLog.getCommandSeq(), null);
        log.info("[策略] 已应用 cabinetNo={} version={} seq={}", form.getCabinetNo(), version,
                cmdLog.getCommandSeq());
        return saved;
    }

    /** 重投失败策略（版本不变；柜侧单调幂等，响应丢失场景安全） */
    public ChargePolicyEntity reapply(Long id) {
        ChargePolicyEntity policy = policyDao.selectById(id);
        if (policy == null) {
            throw new RRException("策略不存在: " + id);
        }
        if (policy.getStatus() != null && policy.getStatus() == 1) {
            throw new RRException("策略已生效，无需重投（改策略请提交新版本）");
        }
        List<ChargePolicyForm.Window> windows = readWindows(policy.getPolicyJson());
        Map<String, Object> body = buildPolicy(policy.getVersion(), policy.getPriority(), windows);
        CommandLogEntity cmdLog = dispatchService.preparePolicy(policy.getCabinetNo(),
                TraceIdFilter.currentOrGenerate());
        try {
            dispatchService.dispatchPolicy(cmdLog, policy.getCabinetNo(), policy.getVersion(), body);
        } catch (RRException e) {
            policyDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChargePolicyEntity>()
                    .eq(ChargePolicyEntity::getId, id)
                    .set(ChargePolicyEntity::getRemark, e.getMessage())
                    .set(ChargePolicyEntity::getUpdateTime, System.currentTimeMillis()));
            throw e;
        }
        policyDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChargePolicyEntity>()
                .eq(ChargePolicyEntity::getId, id)
                .set(ChargePolicyEntity::getStatus, 1)
                .set(ChargePolicyEntity::getCommandSeq, cmdLog.getCommandSeq())
                .set(ChargePolicyEntity::getRemark, null)
                .set(ChargePolicyEntity::getUpdateTime, System.currentTimeMillis()));
        return policyDao.selectById(id);
    }

    public List<ChargePolicyEntity> list(String cabinetNo) {
        return policyDao.selectList(new LambdaQueryWrapper<ChargePolicyEntity>()
                .eq(cabinetNo != null && !cabinetNo.isBlank(), ChargePolicyEntity::getCabinetNo, cabinetNo)
                .orderByDesc(ChargePolicyEntity::getId)
                .last("LIMIT 20"));
    }

    // ---------- 内部 ----------

    private ChargePolicyEntity save(String cabinetNo, int version, String windowsJson, int priority,
                                    int status, Long commandSeq, String remark) {
        long now = System.currentTimeMillis();
        ChargePolicyEntity entity = new ChargePolicyEntity();
        entity.setCabinetNo(cabinetNo);
        entity.setVersion(version);
        entity.setPolicyJson(windowsJson);
        entity.setPriority(priority);
        entity.setStatus(status);
        entity.setCommandSeq(commandSeq);
        entity.setRemark(remark);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        try {
            policyDao.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发提交同版本（latestVersion+1 非原子）撞唯一键：提示重试而非裸 500
            throw new RRException("策略版本冲突（他人已提交 " + version + "），请重新提交");
        }
        return entity;
    }

    private int latestVersion(String cabinetNo) {
        ChargePolicyEntity latest = policyDao.selectOne(new LambdaQueryWrapper<ChargePolicyEntity>()
                .eq(ChargePolicyEntity::getCabinetNo, cabinetNo)
                .orderByDesc(ChargePolicyEntity::getVersion)
                .last("LIMIT 1"));
        return latest == null || latest.getVersion() == null ? 0 : latest.getVersion();
    }

    /** 窗口校验：连续覆盖 0~24（first.start=0、相邻衔接、last.end=24），字段范围合法 */
    private List<ChargePolicyForm.Window> validateWindows(List<ChargePolicyForm.Window> windows) {
        if (windows == null || windows.isEmpty() || windows.size() > 24) {
            throw new RRException("策略窗口必填（1~24 段）");
        }
        List<ChargePolicyForm.Window> sorted = new ArrayList<>(windows);
        sorted.sort(java.util.Comparator.comparing(ChargePolicyForm.Window::getStartHour,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        int expectedStart = 0;
        for (ChargePolicyForm.Window window : sorted) {
            if (window.getStartHour() == null || window.getEndHour() == null
                    || window.getPowerLimitW() == null || window.getFeeFenPerKwh() == null) {
                throw new RRException("窗口字段不完整（startHour/endHour/powerLimitW/feeFenPerKwh）");
            }
            if (window.getStartHour() != expectedStart || window.getEndHour() <= window.getStartHour()
                    || window.getEndHour() > 24) {
                throw new RRException("窗口必须连续覆盖 0~24 且不重叠（当前 " + window.getStartHour()
                        + "~" + window.getEndHour() + "，期望从 " + expectedStart + " 开始）");
            }
            if (window.getPowerLimitW() < 0 || window.getPowerLimitW() > 10000) {
                throw new RRException("功率上限需在 0~10000W");
            }
            if (window.getFeeFenPerKwh() < 0 || window.getFeeFenPerKwh() > 20000) {
                throw new RRException("电价需在 0~20000 分/kWh");
            }
            expectedStart = window.getEndHour();
        }
        if (expectedStart != 24) {
            throw new RRException("窗口必须覆盖到 24 点（当前到 " + expectedStart + "）");
        }
        return sorted;
    }

    private int validatePriority(Integer priority) {
        int value = priority == null ? 2 : priority;
        if (value < 1 || value > 3) {
            throw new RRException("优先级可选 1 高 / 2 中 / 3 低");
        }
        return value;
    }

    private Map<String, Object> buildPolicy(int version, int priority,
                                            List<ChargePolicyForm.Window> windows) {
        List<Map<String, Object>> windowViews = new ArrayList<>();
        for (ChargePolicyForm.Window window : windows) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("startHour", window.getStartHour());
            view.put("endHour", window.getEndHour());
            view.put("powerLimitW", window.getPowerLimitW());
            view.put("feeFenPerKwh", window.getFeeFenPerKwh());
            windowViews.add(view);
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("version", version);
        policy.put("priority", priority);
        policy.put("windows", windowViews);
        return policy;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("策略序列化失败", e);
        }
    }

    private List<ChargePolicyForm.Window> readWindows(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class,
                            ChargePolicyForm.Window.class));
        } catch (Exception e) {
            throw new IllegalStateException("策略反序列化失败", e);
        }
    }
}
