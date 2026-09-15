package com.swapops.server.transfer.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import org.springframework.security.access.prepost.PreAuthorize;

import com.swapops.server.common.Result;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.transfer.entity.TransferTaskEntity;
import com.swapops.server.transfer.service.TransferService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端站间调拨（S4.2，鉴权由 AdminTokenFilter 把关）：
 * 建议（只读）/创建/审批/逐电池出库·入库/取消/列表/详情。
 */
@RestController
@RequestMapping("admin/transfer")
public class AdminTransferController {

    private final TransferService transferService;

    public AdminTransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @GetMapping("/recommend")
        @PreAuthorize("hasAuthority('admin:transfer:read')")
    public Result<List<Map<String, Object>>> recommend() {
        return Result.ok(transferService.recommend());
    }

    @GetMapping
        @PreAuthorize("hasAuthority('admin:transfer:read')")
    public Result<PageResult<TransferTaskEntity>> page(@RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer limit,
                                                       @RequestParam(required = false) Integer status) {
        return Result.ok(transferService.page(page, limit, status));
    }

    @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('admin:transfer:read')")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(transferService.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('admin:transfer:manage')")
    @AdminLog("TRANSFER_CREATE")
    public Result<Map<String, Object>> create(@RequestParam Long fromStationId,
                                              @RequestParam Long toStationId,
                                              @RequestParam Integer count) {
        return Result.ok(transferService.create(fromStationId, toStationId, count,
                AdminContext.currentUsernameOr("admin")));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('admin:transfer:manage')")
    @AdminLog("TRANSFER_APPROVE")
    public Result<TransferTaskEntity> approve(@PathVariable Long id) {
        return Result.ok(transferService.approve(id, AdminContext.currentUsernameOr("admin")));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('admin:transfer:manage')")
    @AdminLog("TRANSFER_CANCEL")
    public Result<TransferTaskEntity> cancel(@PathVariable Long id) {
        return Result.ok(transferService.cancel(id, AdminContext.currentUsernameOr("admin")));
    }

    @PostMapping("/{id}/items/{batteryNo}/out")
    @PreAuthorize("hasAuthority('admin:transfer:manage')")
    @AdminLog("TRANSFER_OUT")
    public Result<Map<String, Object>> out(@PathVariable Long id, @PathVariable String batteryNo) {
        return Result.ok(transferService.out(id, batteryNo, AdminContext.currentUsernameOr("admin")));
    }

    @PostMapping("/{id}/items/{batteryNo}/in")
    @PreAuthorize("hasAuthority('admin:transfer:manage')")
    @AdminLog("TRANSFER_IN")
    public Result<Map<String, Object>> in(@PathVariable Long id, @PathVariable String batteryNo,
                                          @RequestParam Long cellId) {
        return Result.ok(transferService.in(id, batteryNo, cellId, AdminContext.currentUsernameOr("admin")));
    }
}
