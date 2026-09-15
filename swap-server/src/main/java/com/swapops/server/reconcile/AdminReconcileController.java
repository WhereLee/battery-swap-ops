package com.swapops.server.reconcile;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import org.springframework.security.access.prepost.PreAuthorize;

import com.swapops.server.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端对账（S3.5，鉴权由 AdminTokenFilter 把关）：手动触发 + 查询最近一次报告。
 */
@RestController
@RequestMapping("admin/reconcile")
public class AdminReconcileController {

    private final DailyReconcileTask dailyReconcileTask;

    public AdminReconcileController(DailyReconcileTask dailyReconcileTask) {
        this.dailyReconcileTask = dailyReconcileTask;
    }

    @PostMapping("/run")
        @PreAuthorize("hasAuthority('admin:reconcile:run')")
    @AdminLog("RECONCILE_RUN")
    public Result<Map<String, Object>> run() {
        return Result.ok(dailyReconcileTask.runAndStore());
    }

    @GetMapping("/last")
        @PreAuthorize("hasAuthority('admin:reconcile:read')")
    public Result<Map<String, Object>> last() {
        return Result.ok(dailyReconcileTask.lastReport());
    }
}
