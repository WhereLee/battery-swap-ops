package com.swapops.server.user.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.common.RRException;
import com.swapops.server.common.Result;
import com.swapops.server.user.entity.CouponTemplateEntity;
import com.swapops.server.user.form.CouponTemplateForm;
import com.swapops.server.user.service.CouponService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理端优惠券（S7 WP-D）：模板创建/列表 + 批量发放（按用户 id）。
 */
@RestController
@RequestMapping("admin/coupon")
public class AdminCouponController {

    private final CouponService couponService;

    public AdminCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('" + AdminRole.COUPON_READ + "')")
    public Result<List<CouponTemplateEntity>> listTemplates() {
        return Result.ok(couponService.listTemplates());
    }

    @PostMapping("/template")
    @PreAuthorize("hasAuthority('" + AdminRole.COUPON_MANAGE + "')")
    @AdminLog("COUPON_CREATE")
    public Result<CouponTemplateEntity> createTemplate(@RequestBody CouponTemplateForm form) {
        return Result.ok(couponService.createTemplate(form.getName(), form.getValueFen(),
                form.getMinAmountFen(), form.getTotalQuantity(), form.getPerUserLimit(), form.getValidDays()));
    }

    /** 发放：userIds=1,2,3（逗号分隔）；幂等（超限/停用跳过） */
    @PostMapping("/grant")
    @PreAuthorize("hasAuthority('" + AdminRole.COUPON_MANAGE + "')")
    @AdminLog("COUPON_GRANT")
    public Result<Map<String, Object>> grant(@RequestParam Long templateId,
                                             @RequestParam String userIds) {
        List<Long> parsed = new ArrayList<>();
        for (String part : userIds.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                parsed.add(Long.valueOf(trimmed));
            } catch (NumberFormatException e) {
                throw new RRException("用户 id 非法: " + trimmed);
            }
        }
        if (parsed.isEmpty()) {
            throw new RRException("userIds 必填（逗号分隔）");
        }
        int granted = couponService.grant(templateId, parsed);
        return Result.ok(Map.of("templateId", templateId, "requested", parsed.size(), "granted", granted));
    }
}
