package com.swapops.server.user.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import org.springframework.security.access.prepost.PreAuthorize;

import com.swapops.server.common.Result;
import com.swapops.server.user.entity.PlanEntity;
import com.swapops.server.user.form.PlanAdminForm;
import com.swapops.server.user.service.PlanService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端套餐 CRUD（S4.5 批二，鉴权由 AdminTokenFilter 把关）：
 * 变更（创建/编辑/上下架/删除）同时失效目录缓存，用户端立即可见。
 */
@RestController
@RequestMapping("admin/plan")
public class AdminPlanController {

    private final PlanService planService;

    public AdminPlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping
        @PreAuthorize("hasAuthority('admin:plan:read')")
    public Result<List<PlanEntity>> list() {
        return Result.ok(planService.adminList());
    }

    @PostMapping
        @PreAuthorize("hasAuthority('admin:plan:manage')")
    @AdminLog("PLAN_CREATE")
    public Result<PlanEntity> create(@RequestBody PlanAdminForm form) {
        return Result.ok(planService.createPlan(form));
    }

    @PostMapping("/{id}")
        @PreAuthorize("hasAuthority('admin:plan:manage')")
    @AdminLog("PLAN_UPDATE")
    public Result<PlanEntity> update(@PathVariable Long id, @RequestBody PlanAdminForm form) {
        return Result.ok(planService.updatePlan(id, form));
    }

    @PostMapping("/{id}/status")
        @PreAuthorize("hasAuthority('admin:plan:manage')")
    @AdminLog("PLAN_STATUS")
    public Result<PlanEntity> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        return Result.ok(planService.changePlanStatus(id, status));
    }

    @DeleteMapping("/{id}")
        @PreAuthorize("hasAuthority('admin:plan:manage')")
    @AdminLog("PLAN_DELETE")
    public Result<Void> delete(@PathVariable Long id) {
        planService.deletePlan(id);
        return Result.ok();
    }
}
