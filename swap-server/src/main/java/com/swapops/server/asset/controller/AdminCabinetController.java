package com.swapops.server.asset.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import org.springframework.security.access.prepost.PreAuthorize;

import com.swapops.server.asset.form.CabinetAdminForm;
import com.swapops.server.asset.service.AssetAdminService;
import com.swapops.server.common.Result;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.device.entity.CabinetEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端：柜 CRUD（S4.5 批二，secret 响应脱敏）/ 实况 / 维护态操作。
 */
@RestController
@RequestMapping("admin/cabinet")
public class AdminCabinetController {

    private final AssetAdminService assetAdminService;

    public AdminCabinetController(AssetAdminService assetAdminService) {
        this.assetAdminService = assetAdminService;
    }

    @GetMapping
        @PreAuthorize("hasAuthority('admin:asset:read')")
    public Result<PageResult<CabinetEntity>> page(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer limit,
                                                  @RequestParam(required = false) Long stationId,
                                                  @RequestParam(required = false) Integer status,
                                                  @RequestParam(required = false) String cabinetNo) {
        return Result.ok(assetAdminService.pageCabinets(page, limit, stationId, status, cabinetNo));
    }

    @GetMapping("/{cabinetNo}/state")
        @PreAuthorize("hasAuthority('admin:asset:read')")
    public Result<Map<String, Object>> state(@PathVariable String cabinetNo) {
        return Result.ok(assetAdminService.cabinetState(cabinetNo));
    }

    @PostMapping
        @PreAuthorize("hasAuthority('admin:asset:manage')")
    @AdminLog("CABINET_CREATE")
    public Result<CabinetEntity> create(@RequestBody CabinetAdminForm form) {
        return Result.ok(assetAdminService.createCabinet(form));
    }

    @PostMapping("/{id}")
        @PreAuthorize("hasAuthority('admin:asset:manage')")
    @AdminLog("CABINET_UPDATE")
    public Result<CabinetEntity> update(@PathVariable Long id, @RequestBody CabinetAdminForm form) {
        return Result.ok(assetAdminService.updateCabinet(id, form));
    }

    @PostMapping("/{cabinetNo}/status")
        @PreAuthorize("hasAuthority('admin:asset:manage')")
    @AdminLog("CABINET_STATUS")
    public Result<Void> updateStatus(@PathVariable String cabinetNo, @RequestParam Integer status) {
        assetAdminService.updateCabinetStatus(cabinetNo, status);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
        @PreAuthorize("hasAuthority('admin:asset:manage')")
    @AdminLog("CABINET_DELETE")
    public Result<Void> delete(@PathVariable Long id) {
        assetAdminService.deleteCabinet(id);
        return Result.ok();
    }
}
