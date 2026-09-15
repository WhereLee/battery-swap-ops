package com.swapops.server.order.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import org.springframework.security.access.prepost.PreAuthorize;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.server.common.Result;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.order.dao.PaymentRecordDao;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.PaymentRecordEntity;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.SwapOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端：订单查询（A6）+ 订单详情（含支付流水）——运营对账入口。
 */
@RestController
@RequestMapping("admin/order")
public class AdminOrderController {

    private final SwapOrderService swapOrderService;
    private final SwapOrderDao orderDao;
    private final PaymentRecordDao paymentRecordDao;

    public AdminOrderController(SwapOrderService swapOrderService, SwapOrderDao orderDao,
                                PaymentRecordDao paymentRecordDao) {
        this.swapOrderService = swapOrderService;
        this.orderDao = orderDao;
        this.paymentRecordDao = paymentRecordDao;
    }

    @GetMapping
        @PreAuthorize("hasAuthority('admin:order:read')")
    public Result<PageResult<SwapOrderEntity>> page(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer limit,
                                                    @RequestParam(required = false) Long userId,
                                                    @RequestParam(required = false) Integer status,
                                                    @RequestParam(required = false) String orderNo) {
        return Result.ok(swapOrderService.pageOrders(page, limit, userId, status, orderNo));
    }

    @GetMapping("/{orderNo}")
        @PreAuthorize("hasAuthority('admin:order:read')")
    public Result<Map<String, Object>> detail(@PathVariable String orderNo) {
        SwapOrderEntity order = orderDao.selectOne(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getOrderNo, orderNo));
        if (order == null) {
            return Result.error(1, "订单不存在: " + orderNo);
        }
        Map<String, Object> data = new LinkedHashMap<>(swapOrderService.view(order));
        data.put("payments", paymentRecordDao.selectList(new LambdaQueryWrapper<PaymentRecordEntity>()
                .eq(PaymentRecordEntity::getOrderId, order.getId())
                .orderByAsc(PaymentRecordEntity::getId)));
        return Result.ok(data);
    }
}
