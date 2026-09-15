package com.swapops.server.order.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.common.Result;
import com.swapops.server.order.entity.ArrearsRecordEntity;
import com.swapops.server.order.service.ArrearsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端欠费（S7 WP-D）：列表（财务/客服可读）+ 客服减免（留痕审计）。
 */
@RestController
@RequestMapping("admin/arrears")
public class AdminArrearsController {

    private final ArrearsService arrearsService;

    public AdminArrearsController(ArrearsService arrearsService) {
        this.arrearsService = arrearsService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + AdminRole.ARREARS_READ + "')")
    public Result<List<ArrearsRecordEntity>> list(@RequestParam(required = false) Integer status) {
        return Result.ok(arrearsService.listForAdmin(status));
    }

    @PostMapping("/{id}/waive")
    @PreAuthorize("hasAuthority('" + AdminRole.ARREARS_WAIVE + "')")
    @AdminLog("ARREARS_WAIVE")
    public Result<ArrearsRecordEntity> waive(@PathVariable Long id,
                                             @RequestParam(required = false) String remark) {
        return Result.ok(arrearsService.waive(id, remark));
    }
}
