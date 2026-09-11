package com.swapops.server.asset.controller;

import com.swapops.server.asset.service.AssetAdminService;
import com.swapops.server.common.Result;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.device.entity.CabinetEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端：柜查询 / 实况 / 维护态操作。
 */
@RestController
@RequestMapping("admin/cabinet")
public class AdminCabinetController {

    private final AssetAdminService assetAdminService;

    public AdminCabinetController(AssetAdminService assetAdminService) {
        this.assetAdminService = assetAdminService;
    }

    @GetMapping
    public Result<PageResult<CabinetEntity>> page(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer limit,
                                                  @RequestParam(required = false) Long stationId,
                                                  @RequestParam(required = false) Integer status,
                                                  @RequestParam(required = false) String cabinetNo) {
        return Result.ok(assetAdminService.pageCabinets(page, limit, stationId, status, cabinetNo));
    }

    @GetMapping("/{cabinetNo}/state")
    public Result<Map<String, Object>> state(@PathVariable String cabinetNo) {
        return Result.ok(assetAdminService.cabinetState(cabinetNo));
    }

    @PostMapping("/{cabinetNo}/status")
    public Result<Void> updateStatus(@PathVariable String cabinetNo, @RequestParam Integer status) {
        assetAdminService.updateCabinetStatus(cabinetNo, status);
        return Result.ok();
    }
}
