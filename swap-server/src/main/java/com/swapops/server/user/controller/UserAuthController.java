package com.swapops.server.user.controller;

import com.swapops.server.common.Result;
import com.swapops.server.user.form.UserLoginForm;
import com.swapops.server.user.service.UserAccountService;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户端会话（S0.4 U 系列前置）：登录签发 token / 登出吊销。
 */
@RestController
@RequestMapping("user")
public class UserAuthController {

    private static final String USER_TOKEN_HEADER = "X-User-Token";

    private final UserAccountService userAccountService;

    public UserAuthController(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody UserLoginForm form) {
        String token = userAccountService.login(form.getPhone());
        return Result.ok(Map.of("token", token));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        userAccountService.logout(token);
        return Result.ok();
    }
}
