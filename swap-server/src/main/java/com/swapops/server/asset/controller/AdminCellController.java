package com.swapops.server.asset.controller;

import com.swapops.server.asset.service.AssetAdminService;
import com.swapops.server.common.Result;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.device.entity.CellEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：仓查询 + 故障/停用/恢复。
 */
@RestController
@RequestMapping("admin/cell")
public class AdminCellController {

    private final AssetAdminService assetAdminService;

    public AdminCellController(AssetAdminService assetAdminService) {
        this.assetAdminService = assetAdminService;
    }

    @GetMapping
    public Result<PageResult<CellEntity>> page(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer limit,
                                               @RequestParam(required = false) String cabinetNo,
                                               @RequestParam(required = false) Integer status) {
        return Result.ok(assetAdminService.pageCells(page, limit, cabinetNo, status));
    }

    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        assetAdminService.updateCellStatus(id, status);
        return Result.ok();
    }
}
