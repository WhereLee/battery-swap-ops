package com.swapops.server.web.view.controller;

import com.swapops.server.admin.annotation.DataFilter;
import com.swapops.server.common.Result;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.web.view.AdminViews;
import com.swapops.server.web.view.service.AdminFinanceViewService;
import com.swapops.server.web.view.service.AdminObservationViewService;
import com.swapops.server.web.view.service.AdminWorkflowViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端视图接口（S8 BFF 视图层）：面向"一个页面一次请求"的读聚合 + 能力位。
 *
 * <p>与各域接口的分工：域接口（{@code /admin/alarm}、{@code /admin/work-order} 等）是<b>能力</b>，
 * 本层是<b>页面所需的组合</b>；本层只读、不写、不含业务规则，写操作仍调各域端点。
 *
 * <p>数据范围纪律：标注 {@code @DataFilter} 的端点，其查询路径必然调用
 * {@code DataScopeSupport.applyStation/requireStationAccess}（切面做空转自检）。
 * 告警页、建议单页与结算页<b>有意不标注</b>——{@code alarm}/{@code agent_action}/{@code settlement_statement}
 * 三表无站点归属列（系统级/跨站级/代理维度资源，全局口径），标注只会让自检误报"空转"，见 S8 方案 §4.8。
 */
@Tag(name = "管理端视图（BFF）", description = "面向页面的只读聚合 + 能力位；前端一次请求拿一页所需数据")
@RestController
@RequestMapping("admin/view")
public class AdminViewController {

    private final AdminObservationViewService observationViewService;
    private final AdminWorkflowViewService workflowViewService;
    private final AdminFinanceViewService financeViewService;

    public AdminViewController(AdminObservationViewService observationViewService,
                               AdminWorkflowViewService workflowViewService,
                               AdminFinanceViewService financeViewService) {
        this.observationViewService = observationViewService;
        this.workflowViewService = workflowViewService;
        this.financeViewService = financeViewService;
    }

    @Operation(summary = "看板视图（满电保有率/站点可用率/周转率）")
    @GetMapping("/dashboard")
        @PreAuthorize("hasAuthority('admin:dashboard:read')")
    @DataFilter("view-dashboard")
    public Result<AdminViews.DashboardVO> dashboard() {
        return Result.ok(observationViewService.dashboard());
    }

    @Operation(summary = "告警分页视图（带是否已有工单 + 可执行动作）")
    @GetMapping("/alarm")
        @PreAuthorize("hasAuthority('admin:alarm:read')")
    public Result<PageResult<AdminViews.AlarmItemVO>> alarmPage(@RequestParam(required = false) Integer page,
                                                                @RequestParam(required = false) Integer limit,
                                                                @RequestParam(required = false) Integer handled) {
        return Result.ok(observationViewService.alarmPage(page, limit, handled));
    }

    @Operation(summary = "Agent 建议单分页视图（S6 人工处置入口，带 confirm/reject 能力位）")
    @GetMapping("/agent-action")
        @PreAuthorize("hasAuthority('admin:suggestion:read')")
    public Result<PageResult<AdminViews.SuggestionVO>> agentActionPage(@RequestParam(required = false) Integer page,
                                                                       @RequestParam(required = false) Integer limit,
                                                                       @RequestParam(required = false) Integer status) {
        return Result.ok(observationViewService.agentActionPage(page, limit, status));
    }

    @Operation(summary = "工单分页视图（带五步动作链的能力位）")
    @GetMapping("/work-order")
        @PreAuthorize("hasAuthority('admin:work-order:read')")
    @DataFilter("view-work-order-page")
    public Result<PageResult<AdminViews.WorkOrderVO>> workOrderPage(@RequestParam(required = false) Integer page,
                                                                    @RequestParam(required = false) Integer limit,
                                                                    @RequestParam(required = false) Integer status) {
        return Result.ok(workflowViewService.workOrderPage(page, limit, status));
    }

    @Operation(summary = "工单详情视图（工单 + 流转日志 + 来源告警）")
    @GetMapping("/work-order/{id}")
        @PreAuthorize("hasAuthority('admin:work-order:read')")
    @DataFilter("view-work-order-detail")
    public Result<AdminViews.WorkOrderDetailVO> workOrderDetail(@PathVariable Long id) {
        return Result.ok(workflowViewService.workOrderDetail(id));
    }

    @Operation(summary = "柜详情视图（档案 + 仓与电池 + 未处理告警 + 进行中订单 + 最近指令）")
    @GetMapping("/cabinet/{cabinetNo}")
        @PreAuthorize("hasAuthority('admin:asset:read')")
    @DataFilter("view-cabinet-detail")
    public Result<AdminViews.CabinetDetailVO> cabinetDetail(@PathVariable String cabinetNo) {
        return Result.ok(workflowViewService.cabinetDetail(cabinetNo));
    }

    @Operation(summary = "订单详情视图（时间线 + 支付/退款流水 + 可退金额）")
    @GetMapping("/order/{orderNo}")
        @PreAuthorize("hasAuthority('admin:order:read')")
    @DataFilter("view-order-detail")
    public Result<AdminViews.OrderDetailVO> orderDetail(@PathVariable String orderNo) {
        return Result.ok(workflowViewService.orderDetail(orderNo));
    }

    @Operation(summary = "订单分页视图（柜号/可退金额/资金动作能力位，不含内部列）")
    @GetMapping("/order")
        @PreAuthorize("hasAuthority('admin:order:read')")
    @DataFilter("view-order-page")
    public Result<PageResult<AdminViews.OrderListItemVO>> orderPage(@RequestParam(required = false) Integer page,
                                                                    @RequestParam(required = false) Integer limit,
                                                                    @RequestParam(required = false) Long userId,
                                                                    @RequestParam(required = false) Integer status,
                                                                    @RequestParam(required = false) String orderNo) {
        return Result.ok(workflowViewService.orderPage(page, limit, userId, status, orderNo));
    }

    @Operation(summary = "柜分页视图（柜列表，密钥脱敏 + 站点范围过滤）")
    @GetMapping("/cabinet")
        @PreAuthorize("hasAuthority('admin:asset:read')")
    @DataFilter("view-cabinet-page")
    public Result<PageResult<AdminViews.CabinetVO>> cabinetPage(@RequestParam(required = false) Integer page,
                                                                @RequestParam(required = false) Integer limit,
                                                                @RequestParam(required = false) Long stationId,
                                                                @RequestParam(required = false) Integer status,
                                                                @RequestParam(required = false) String cabinetNo) {
        return Result.ok(workflowViewService.cabinetPage(page, limit, stationId, status, cabinetNo));
    }

    @Operation(summary = "结算单分页视图（带确认/打款能力位）")
    @GetMapping("/settlement")
        @PreAuthorize("hasAuthority('admin:settlement:read')")
    public Result<PageResult<AdminViews.SettlementVO>> settlementPage(@RequestParam(required = false) Integer page,
                                                                      @RequestParam(required = false) Integer limit,
                                                                      @RequestParam(required = false) Long agentId,
                                                                      @RequestParam(required = false) Integer status) {
        return Result.ok(financeViewService.settlementPage(page, limit, agentId, status));
    }

    @Operation(summary = "结算单详情视图（单头 + 分账流水）")
    @GetMapping("/settlement/{id}")
        @PreAuthorize("hasAuthority('admin:settlement:read')")
    public Result<AdminViews.SettlementDetailVO> settlementDetail(@PathVariable Long id) {
        return Result.ok(financeViewService.settlementDetail(id));
    }
}
