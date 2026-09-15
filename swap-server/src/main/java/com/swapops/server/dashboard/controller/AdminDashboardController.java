package com.swapops.server.dashboard.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import org.springframework.security.access.prepost.PreAuthorize;

import com.swapops.server.common.Result;
import com.swapops.server.dashboard.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端运营看板（S4.5 批一，鉴权由 AdminTokenFilter 把关）。
 */
@RestController
@RequestMapping("admin/dashboard")
public class AdminDashboardController {

    private final DashboardService dashboardService;

    public AdminDashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/overview")
        @PreAuthorize("hasAuthority('admin:dashboard:read')")
    public Result<Map<String, Object>> overview() {
        return Result.ok(dashboardService.overview());
    }
}
