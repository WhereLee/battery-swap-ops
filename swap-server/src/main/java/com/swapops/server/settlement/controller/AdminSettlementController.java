package com.swapops.server.settlement.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import com.swapops.server.common.Result;
import com.swapops.server.settlement.entity.OrderSettlementEntity;
import com.swapops.server.settlement.entity.SettlementStatementEntity;
import com.swapops.server.settlement.service.SettlementService;
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
 * 管理端结算（S7 WP-B）：流水查询 / 结算单（生成→确认→打款，顺序批）/ 报表（admin:settlement:*、admin:report:read）。
 */
@RestController
@RequestMapping("admin/settlement")
public class AdminSettlementController {

    private final SettlementService settlementService;

    public AdminSettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + AdminRole.SETTLEMENT_READ + "')")
    public Result<List<SettlementStatementEntity>> list(@RequestParam(required = false) Long agentId,
                                                        @RequestParam(required = false) Integer status) {
        return Result.ok(settlementService.listStatements(agentId, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + AdminRole.SETTLEMENT_READ + "')")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(settlementService.statementDetail(id));
    }

    /** 某订单分账流水（排障/对账） */
    @GetMapping("/ledger")
    @PreAuthorize("hasAuthority('" + AdminRole.SETTLEMENT_READ + "')")
    public Result<List<OrderSettlementEntity>> ledger(@RequestParam String orderNo) {
        return Result.ok(settlementService.ledgerByOrder(orderNo));
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('" + AdminRole.SETTLEMENT_MANAGE + "')")
    @AdminLog("SETTLEMENT_GENERATE")
    public Result<SettlementStatementEntity> generate(@RequestParam Long agentId,
                                                      @RequestParam Long periodStart,
                                                      @RequestParam Long periodEnd) {
        return Result.ok(settlementService.generate(agentId, periodStart, periodEnd,
                AdminContext.currentUsernameOr("admin")));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('" + AdminRole.SETTLEMENT_MANAGE + "')")
    @AdminLog("SETTLEMENT_CONFIRM")
    public Result<SettlementStatementEntity> confirm(@PathVariable Long id) {
        return Result.ok(settlementService.confirm(id, AdminContext.currentUsernameOr("admin")));
    }

    @PostMapping("/{id}/paid")
    @PreAuthorize("hasAuthority('" + AdminRole.SETTLEMENT_MANAGE + "')")
    @AdminLog("SETTLEMENT_PAID")
    public Result<SettlementStatementEntity> paid(@PathVariable Long id) {
        return Result.ok(settlementService.pay(id, AdminContext.currentUsernameOr("admin")));
    }

    @GetMapping("/report")
    @PreAuthorize("hasAuthority('" + AdminRole.REPORT_READ + "')")
    public Result<Map<String, Object>> report(@RequestParam Long from, @RequestParam Long to) {
        return Result.ok(settlementService.report(from, to));
    }
}
