package com.swapops.server.alarm.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import org.springframework.security.access.prepost.PreAuthorize;

import com.swapops.server.alarm.entity.AlarmEntity;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.common.RRException;
import com.swapops.server.common.Result;
import com.swapops.server.common.web.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端告警（S3.6，鉴权由 AdminTokenFilter 把关）：默认未处理列表 + 人工处理。
 * handler 取管理端上下文（无用户上下文时为 null——管理 token 不映射用户，S4 RBAC 补）。
 */
@RestController
@RequestMapping("admin/alarm")
public class AdminAlarmController {

    private final AlarmService alarmService;

    public AdminAlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    @GetMapping
        @PreAuthorize("hasAuthority('admin:alarm:read')")
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) Integer handled,
                                                  @RequestParam(required = false, defaultValue = "100") Integer limit) {
        List<AlarmEntity> alarms = alarmService.list(handled == null ? 0 : handled, limit);
        return Result.ok(alarms.stream().map(this::view).toList());
    }

    @PostMapping("/{id}/handle")
    @PreAuthorize("hasAuthority('admin:alarm:handle')")
    @AdminLog("ALARM_HANDLE")
    public Result<Map<String, Object>> handle(@PathVariable Long id) {
        Long userId = AdminContext.currentAdminId();
        if (!alarmService.handle(id, userId)) {
            throw new RRException("告警不存在或已处理: " + id);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("alarmId", id);
        view.put("handled", true);
        return Result.ok(view);
    }

    private Map<String, Object> view(AlarmEntity alarm) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", alarm.getId());
        view.put("deviceType", alarm.getDeviceType());
        view.put("deviceNo", alarm.getDeviceNo());
        view.put("alarmType", alarm.getAlarmType());
        view.put("content", alarm.getContent());
        view.put("handled", alarm.getHandled());
        view.put("handler", alarm.getHandler());
        view.put("createTime", alarm.getCreateTime());
        view.put("handledTime", alarm.getHandledTime());
        return view;
    }
}
