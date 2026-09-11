package com.swapops.server.reconcile;

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
    public Result<Map<String, Object>> run() {
        return Result.ok(dailyReconcileTask.runAndStore());
    }

    @GetMapping("/last")
    public Result<Map<String, Object>> last() {
        return Result.ok(dailyReconcileTask.lastReport());
    }
}
