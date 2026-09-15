package com.swapops.server.workorder.controller;

import com.swapops.server.common.Result;
import com.swapops.server.common.ratelimit.RateLimit;
import com.swapops.server.common.ratelimit.RateLimitDimension;
import com.swapops.server.common.web.UserContext;
import com.swapops.server.workorder.entity.WorkOrderEntity;
import com.swapops.server.workorder.form.UserReportForm;
import com.swapops.server.workorder.service.UserReportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户端报障（S7 WP-D）：自动转工单（同用户同柜 10 分钟去重）。
 * 限流：同用户 3 次/分钟（防刷单）。
 */
@RestController
@RequestMapping("user")
public class UserReportController {

    private final UserReportService userReportService;

    public UserReportController(UserReportService userReportService) {
        this.userReportService = userReportService;
    }

    @RateLimit(name = "user-report", dimension = RateLimitDimension.USER, permits = 3, windowSeconds = 60)
    @PostMapping("/report")
    public Result<Map<String, Object>> report(@RequestBody UserReportForm form) {
        WorkOrderEntity order = userReportService.report(UserContext.require(), form.getCabinetNo(),
                form.getCellNo(), form.getType(), form.getDescription());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("woNo", order.getWoNo());
        view.put("status", order.getStatus());
        view.put("title", order.getTitle());
        return Result.ok(view);
    }
}
