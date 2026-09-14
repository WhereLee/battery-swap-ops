package com.swapops.server.asset.controller;

import com.swapops.server.common.Result;
import com.swapops.server.order.service.AllocationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运维端点（S5 运维审查补，鉴权由 AdminTokenFilter 把关）：
 * Redis 故障/数据丢失后的分配集合重建入口——此前 rebuildFromDb 只有内部调用路径，
 * 生产无 dev 端点时 Redis 恢复无法触发重建（见 ops-troubleshooting.md §2.1）。
 */
@RestController
@RequestMapping("admin/ops")
public class AdminOpsController {

    private final AllocationService allocationService;

    public AdminOpsController(AllocationService allocationService) {
        this.allocationService = allocationService;
    }

    /** 按 DB 真值全量重建可分配集合（幂等；锁定中/不可用柜不进池） */
    @PostMapping("/rebuild-alloc")
    public Result<Map<String, Object>> rebuildAlloc() {
        allocationService.rebuildFromDb();
        return Result.ok(Map.of("rebuilt", true));
    }
}
