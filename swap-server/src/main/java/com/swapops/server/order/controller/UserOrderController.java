package com.swapops.server.order.controller;

import com.swapops.server.common.RRException;
import com.swapops.server.common.Result;
import com.swapops.server.common.web.UserContext;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.form.CreateOrderForm;
import com.swapops.server.order.service.SwapOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户端：下单（U1，Idempotency-Key 必填）/ 查询（U2）/ 取消（U3）。
 * 开仓下发在事务提交后执行；失败由订单域补偿关闭。
 */
@RestController
@RequestMapping("user")
public class UserOrderController {

    private static final String IDEM_HEADER = "Idempotency-Key";

    private final SwapOrderService swapOrderService;

    public UserOrderController(SwapOrderService swapOrderService) {
        this.swapOrderService = swapOrderService;
    }

    /** 下单限流：同用户 5 单/分钟（防误点/脚本刷单） */
    @com.swapops.server.common.ratelimit.RateLimit(name = "order-create",
            dimension = com.swapops.server.common.ratelimit.RateLimitDimension.USER, permits = 5, windowSeconds = 60)
    @PostMapping("/order")
    public Result<Map<String, Object>> create(@RequestBody CreateOrderForm form,
                                              @RequestHeader(value = IDEM_HEADER, required = false) String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            throw new RRException("缺少幂等键 Idempotency-Key");
        }
        Long userId = UserContext.require();
        SwapOrderEntity order = swapOrderService.create(userId, form, idemKey);
        swapOrderService.sendOpenCommand(order.getOrderNo());
        SwapOrderEntity fresh = swapOrderService.requireOwn(order.getOrderNo(), userId);
        return Result.ok(swapOrderService.view(fresh));
    }

    @GetMapping("/order/{orderNo}")
    public Result<Map<String, Object>> detail(@PathVariable String orderNo) {
        SwapOrderEntity order = swapOrderService.requireOwn(orderNo, UserContext.require());
        return Result.ok(swapOrderService.view(order));
    }

    @PostMapping("/order/{orderNo}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable String orderNo) {
        SwapOrderEntity order = swapOrderService.cancel(orderNo, UserContext.require());
        return Result.ok(swapOrderService.view(order));
    }
}
