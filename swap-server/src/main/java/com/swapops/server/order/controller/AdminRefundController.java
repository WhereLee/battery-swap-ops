package com.swapops.server.order.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import org.springframework.security.access.prepost.PreAuthorize;

import com.swapops.server.common.RRException;
import com.swapops.server.common.Result;
import com.swapops.server.order.entity.RefundRecordEntity;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.SwapOrderService;
import com.swapops.server.order.service.pay.RefundService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端退款（S3.4）：人工触发（鉴权由 AdminTokenFilter 把关）。
 * 金额缺省=自动核算可退金额；同订单同原因（ADMIN_MANUAL）幂等只退一次。
 */
@RestController
@RequestMapping("admin/refund")
public class AdminRefundController {

    private static final String REASON = "ADMIN_MANUAL";

    private final SwapOrderService swapOrderService;
    private final RefundService refundService;

    public AdminRefundController(SwapOrderService swapOrderService, RefundService refundService) {
        this.swapOrderService = swapOrderService;
        this.refundService = refundService;
    }

    @PostMapping("/{orderNo}")
        @PreAuthorize("hasAuthority('admin:refund:create')")
    @AdminLog("REFUND_CREATE")
    public Result<Map<String, Object>> refund(@PathVariable String orderNo,
                                              @RequestParam(required = false) Integer amountFen) {
        SwapOrderEntity order = swapOrderService.findByOrderNo(orderNo);
        if (order == null) {
            throw new RRException("订单不存在: " + orderNo);
        }
        // S7 WP-0：已完成订单资金已结算入账（含分账），不允许走人工退款通道二次退现；如需退还走冲正流程
        if (order.getStatus() != null
                && order.getStatus() == com.swapops.contract.OrderStatus.COMPLETED.getCode()) {
            throw new RRException("已完成订单不支持人工退款（资金已结算，退款请走冲正流程）: " + orderNo);
        }
        int amount = amountFen == null ? refundService.refundableAmount(order.getId()) : amountFen;
        if (amount < 0) {
            throw new RRException("退款金额非法: " + amount);
        }
        if (amount == 0) {
            throw new RRException("该订单无可退金额: " + orderNo);
        }
        RefundRecordEntity record = refundService.refund(order.getId(), order.getUserId(), amount, REASON);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("refundNo", record.getRefundNo());
        view.put("orderNo", orderNo);
        view.put("amountFen", record.getAmountFen());
        view.put("reason", record.getReason());
        view.put("status", record.getStatus());
        return Result.ok(view);
    }
}
