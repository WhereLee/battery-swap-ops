package com.swapops.server.common.cache;

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
 * 缓存运维入口（S3.8 WP1，鉴权由 AdminTokenFilter 把关）：运行指标（命中/回源/降级计数）。
 * 只读——失效一律走业务写路径（保证"谁改谁清"语义可审计）。
 */
@RestController
@RequestMapping("admin/cache")
public class CacheAdminController {

    private final TwoLevelCacheService cacheService;

    public CacheAdminController(TwoLevelCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @GetMapping("/stats")
        @PreAuthorize("hasAuthority('admin:cache:read')")
    public Result<Map<String, Long>> stats() {
        return Result.ok(cacheService.stats());
    }
}
