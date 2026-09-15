package com.swapops.server.user.controller;

import com.swapops.server.common.Result;
import com.swapops.server.common.web.UserContext;
import com.swapops.server.user.entity.UserCouponEntity;
import com.swapops.server.user.service.CouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户端我的券（S7 WP-D）。 */
@RestController
@RequestMapping("user")
public class UserCouponController {

    private final CouponService couponService;

    public UserCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping("/coupons")
    public Result<List<UserCouponEntity>> list(@RequestParam(required = false) Integer status) {
        return Result.ok(couponService.listUserCoupons(UserContext.require(), status));
    }
}
