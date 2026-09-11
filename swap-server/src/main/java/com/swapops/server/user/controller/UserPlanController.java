package com.swapops.server.user.controller;

import com.swapops.server.common.RRException;
import com.swapops.server.common.Result;
import com.swapops.server.common.web.UserContext;
import com.swapops.server.user.entity.PlanEntity;
import com.swapops.server.user.entity.UserPlanEntity;
import com.swapops.server.user.service.PlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户端：套餐列表 + 购买（模拟支付；Idempotency-Key 必填）。
 */
@RestController
@RequestMapping("user")
public class UserPlanController {

    private static final String IDEM_HEADER = "Idempotency-Key";

    private final PlanService planService;

    public UserPlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping("/plans")
    public Result<List<PlanEntity>> plans() {
        return Result.ok(planService.listActive());
    }

    @PostMapping("/plans/{planId}/purchase")
    public Result<Map<String, Object>> purchase(@PathVariable Long planId,
                                                @RequestHeader(value = IDEM_HEADER, required = false) String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            throw new RRException("缺少幂等键 Idempotency-Key");
        }
        UserPlanEntity userPlan = planService.purchase(UserContext.require(), planId, idemKey);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userPlanId", userPlan.getId());
        data.put("remainingTimes", userPlan.getRemainingTimes());
        data.put("endTime", userPlan.getEndTime());
        return Result.ok(data);
    }
}
