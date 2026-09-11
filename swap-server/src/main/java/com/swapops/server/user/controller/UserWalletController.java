package com.swapops.server.user.controller;

import com.swapops.server.common.Result;
import com.swapops.server.common.web.UserContext;
import com.swapops.server.user.entity.PlanEntity;
import com.swapops.server.user.entity.UserPlanEntity;
import com.swapops.server.user.entity.WalletEntity;
import com.swapops.server.user.service.PlanService;
import com.swapops.server.user.service.WalletService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户端：钱包与套餐摘要（U5）。
 */
@RestController
@RequestMapping("user")
public class UserWalletController {

    private final WalletService walletService;
    private final PlanService planService;

    public UserWalletController(WalletService walletService, PlanService planService) {
        this.walletService = walletService;
        this.planService = planService;
    }

    @GetMapping("/wallet")
    public Result<Map<String, Object>> wallet() {
        Long userId = UserContext.require();
        WalletEntity wallet = walletService.getByUserId(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("balanceFen", wallet == null || wallet.getBalanceFen() == null ? 0 : wallet.getBalanceFen());
        data.put("depositFen", wallet == null || wallet.getDepositFen() == null ? 0 : wallet.getDepositFen());
        UserPlanEntity active = planService.findUsablePlan(userId, System.currentTimeMillis());
        if (active != null) {
            PlanEntity plan = planService.listActive().stream()
                    .filter(p -> p.getId().equals(active.getPlanId()))
                    .findFirst().orElse(null);
            Map<String, Object> planView = new LinkedHashMap<>();
            planView.put("userPlanId", active.getId());
            planView.put("planId", active.getPlanId());
            planView.put("name", plan == null ? null : plan.getName());
            planView.put("type", plan == null ? null : plan.getPlanType());
            planView.put("remainingTimes", active.getRemainingTimes());
            planView.put("endTime", active.getEndTime());
            data.put("activePlan", planView);
        } else {
            data.put("activePlan", null);
        }
        return Result.ok(data);
    }
}
