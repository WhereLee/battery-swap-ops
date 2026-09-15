package com.swapops.server.workorder.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import org.springframework.security.access.prepost.PreAuthorize;

import com.swapops.server.common.Result;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.workorder.entity.WorkOrderEntity;
import com.swapops.server.workorder.service.WorkOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端工单（S4.4，鉴权由 AdminTokenFilter 把关）：转单/分诊/派单/处置/验收/关闭 + 列表/详情。
 */
@RestController
@RequestMapping("admin/work-order")
public class AdminWorkOrderController {

    private final WorkOrderService workOrderService;

    public AdminWorkOrderController(WorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    @GetMapping
        @PreAuthorize("hasAuthority('admin:work-order:read')")
    public Result<PageResult<WorkOrderEntity>> page(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer limit,
                                                    @RequestParam(required = false) Integer status) {
        return Result.ok(workOrderService.page(page, limit, status));
    }

    @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('admin:work-order:read')")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(workOrderService.detail(id));
    }

    @PostMapping("/from-alarm/{alarmId}")
        @PreAuthorize("hasAuthority('admin:work-order:manage')")
    @AdminLog("WORK_ORDER_FROM_ALARM")
    public Result<WorkOrderEntity> fromAlarm(@PathVariable Long alarmId,
                                             @RequestParam(required = false) String severity) {
        return Result.ok(workOrderService.createFromAlarm(alarmId, severity));
    }

    @PostMapping("/{id}/triage")
        @PreAuthorize("hasAuthority('admin:work-order:manage')")
    @AdminLog("WORK_ORDER_TRIAGE")
    public Result<WorkOrderEntity> triage(@PathVariable Long id,
                                          @RequestParam(required = false) String severity,
                                          @RequestParam(required = false) String remark) {
        return Result.ok(workOrderService.triage(id, severity, remark));
    }

    @PostMapping("/{id}/assign")
        @PreAuthorize("hasAuthority('admin:work-order:manage')")
    @AdminLog("WORK_ORDER_ASSIGN")
    public Result<WorkOrderEntity> assign(@PathVariable Long id,
                                          @RequestParam Long handlerId,
                                          @RequestParam(required = false) String remark) {
        return Result.ok(workOrderService.assign(id, handlerId, remark));
    }

    @PostMapping("/{id}/start")
        @PreAuthorize("hasAuthority('admin:work-order:manage')")
    @AdminLog("WORK_ORDER_START")
    public Result<WorkOrderEntity> start(@PathVariable Long id,
                                         @RequestParam(required = false) String remark) {
        return Result.ok(workOrderService.start(id, remark));
    }

    @PostMapping("/{id}/verify")
        @PreAuthorize("hasAuthority('admin:work-order:manage')")
    @AdminLog("WORK_ORDER_VERIFY")
    public Result<WorkOrderEntity> verify(@PathVariable Long id,
                                          @RequestParam(required = false) String remark) {
        return Result.ok(workOrderService.verify(id, remark));
    }

    @PostMapping("/{id}/close")
        @PreAuthorize("hasAuthority('admin:work-order:manage')")
    @AdminLog("WORK_ORDER_CLOSE")
    public Result<WorkOrderEntity> close(@PathVariable Long id,
                                         @RequestParam(required = false) String remark) {
        return Result.ok(workOrderService.close(id, remark));
    }
}
