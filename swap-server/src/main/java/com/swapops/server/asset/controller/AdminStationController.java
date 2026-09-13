package com.swapops.server.asset.controller;

import com.swapops.server.asset.entity.StationEntity;
import com.swapops.server.asset.form.StationAdminForm;
import com.swapops.server.asset.service.AssetAdminService;
import com.swapops.server.common.Result;
import com.swapops.server.common.utils.PageResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：站点 CRUD（S4.5 批二）+ 停启用（鉴权由 AdminTokenFilter 把关）。
 */
@RestController
@RequestMapping("admin/station")
public class AdminStationController {

    private final AssetAdminService assetAdminService;

    public AdminStationController(AssetAdminService assetAdminService) {
        this.assetAdminService = assetAdminService;
    }

    @GetMapping
    public Result<PageResult<StationEntity>> page(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer limit,
                                                  @RequestParam(required = false) Integer status) {
        return Result.ok(assetAdminService.pageStations(page, limit, status));
    }

    @PostMapping
    public Result<StationEntity> create(@RequestBody StationAdminForm form) {
        return Result.ok(assetAdminService.createStation(form));
    }

    @PostMapping("/{id}")
    public Result<StationEntity> update(@PathVariable Long id, @RequestBody StationAdminForm form) {
        return Result.ok(assetAdminService.updateStation(id, form));
    }

    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        assetAdminService.updateStationStatus(id, status);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        assetAdminService.deleteStation(id);
        return Result.ok();
    }
}
