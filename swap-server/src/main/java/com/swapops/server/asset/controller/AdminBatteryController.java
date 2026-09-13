package com.swapops.server.asset.controller;

import com.swapops.server.asset.form.BatteryAdminForm;
import com.swapops.server.asset.service.AssetAdminService;
import com.swapops.server.common.Result;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.device.entity.BatteryEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：电池登记 CRUD（S4.5 批二）+ 台账查询 + 维修/退役/恢复。
 */
@RestController
@RequestMapping("admin/battery")
public class AdminBatteryController {

    private final AssetAdminService assetAdminService;

    public AdminBatteryController(AssetAdminService assetAdminService) {
        this.assetAdminService = assetAdminService;
    }

    @GetMapping
    public Result<PageResult<BatteryEntity>> page(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer limit,
                                                  @RequestParam(required = false) Integer status,
                                                  @RequestParam(required = false) String batteryNo) {
        return Result.ok(assetAdminService.pageBatteries(page, limit, status, batteryNo));
    }

    /** 电池健康档案（S4.1）：SOH 分级 + swaps/cycle 计数 + 最近循环流水 */
    @GetMapping("/{batteryNo}/health")
    public Result<java.util.Map<String, Object>> health(@PathVariable String batteryNo) {
        return Result.ok(assetAdminService.batteryHealth(batteryNo));
    }

    @PostMapping
    public Result<BatteryEntity> create(@RequestBody BatteryAdminForm form) {
        return Result.ok(assetAdminService.createBattery(form));
    }

    @PostMapping("/{batteryNo}")
    public Result<BatteryEntity> update(@PathVariable String batteryNo, @RequestBody BatteryAdminForm form) {
        return Result.ok(assetAdminService.updateBattery(batteryNo, form));
    }

    @PostMapping("/{batteryNo}/status")
    public Result<Void> updateStatus(@PathVariable String batteryNo, @RequestParam Integer status) {
        assetAdminService.updateBatteryStatus(batteryNo, status);
        return Result.ok();
    }

    @DeleteMapping("/{batteryNo}")
    public Result<Void> delete(@PathVariable String batteryNo) {
        assetAdminService.deleteBattery(batteryNo);
        return Result.ok();
    }
}
