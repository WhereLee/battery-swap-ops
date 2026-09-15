package com.swapops.server.common.ratelimit;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import org.springframework.security.access.prepost.PreAuthorize;

import com.swapops.server.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 限流指标（S3.8 WP2，鉴权由 AdminTokenFilter 把关）：按限流器名统计放行/拒绝。
 */
@RestController
@RequestMapping("admin/ratelimit")
public class RateLimitStatsController {

    private final RateLimitAspect rateLimitAspect;

    public RateLimitStatsController(RateLimitAspect rateLimitAspect) {
        this.rateLimitAspect = rateLimitAspect;
    }

    @GetMapping("/stats")
        @PreAuthorize("hasAuthority('admin:ratelimit:read')")
    public Result<Map<String, Long>> stats() {
        return Result.ok(rateLimitAspect.stats());
    }
}
