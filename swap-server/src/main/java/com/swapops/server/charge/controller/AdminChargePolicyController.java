package com.swapops.server.charge.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import org.springframework.security.access.prepost.PreAuthorize;

import com.swapops.server.charge.entity.ChargePolicyEntity;
import com.swapops.server.charge.form.ChargePolicyForm;
import com.swapops.server.charge.service.ChargePolicyService;
import com.swapops.server.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端充电策略（S4.3，鉴权由 AdminTokenFilter 把关）：下发新版本 / 版本历史 / 失败重投。
 */
@RestController
@RequestMapping("admin/charge-policy")
public class AdminChargePolicyController {

    private final ChargePolicyService chargePolicyService;

    public AdminChargePolicyController(ChargePolicyService chargePolicyService) {
        this.chargePolicyService = chargePolicyService;
    }

    @PostMapping
        @PreAuthorize("hasAuthority('admin:charge-policy:manage')")
    @AdminLog("CHARGE_POLICY_APPLY")
    public Result<ChargePolicyEntity> apply(@RequestBody ChargePolicyForm form) {
        return Result.ok(chargePolicyService.apply(form));
    }

    @GetMapping
        @PreAuthorize("hasAuthority('admin:charge-policy:read')")
    public Result<List<ChargePolicyEntity>> list(@RequestParam(required = false) String cabinetNo) {
        return Result.ok(chargePolicyService.list(cabinetNo));
    }

    @PostMapping("/{id}/reapply")
        @PreAuthorize("hasAuthority('admin:charge-policy:manage')")
    @AdminLog("CHARGE_POLICY_REAPPLY")
    public Result<ChargePolicyEntity> reapply(@PathVariable Long id) {
        return Result.ok(chargePolicyService.reapply(id));
    }
}
