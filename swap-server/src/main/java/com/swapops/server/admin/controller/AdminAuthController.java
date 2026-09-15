package com.swapops.server.admin.controller;

import com.swapops.server.admin.service.AdminAuthService;
import com.swapops.server.common.RRException;
import com.swapops.server.common.Result;
import com.swapops.server.common.ratelimit.RateLimit;
import com.swapops.server.common.ratelimit.RateLimitDimension;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端会话（S7 WP-A）：登录签发会话 token / 登出吊销。
 * 登录端点免鉴权（安全链白名单），撞库防护走 IP 限流。
 */
@RestController
@RequestMapping("admin/auth")
public class AdminAuthController {

    private static final String TOKEN_HEADER = "X-Admin-Token";

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    /** 登录：账号+密码，失败同样计入审计（见 AdminAuthService） */
    @RateLimit(name = "admin-login", dimension = RateLimitDimension.IP, permits = 5, windowSeconds = 5)
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody AdminLoginForm form) {
        if (form == null || form.getUsername() == null || form.getUsername().isBlank()
                || form.getPassword() == null || form.getPassword().isEmpty()) {
            throw new RRException("账号与密码必填");
        }
        String token = adminAuthService.login(form.getUsername(), form.getPassword());
        return Result.ok(Map.of("token", token));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        adminAuthService.logout(token);
        return Result.ok();
    }

    /** 登录表单（仅本控制器使用） */
    public static class AdminLoginForm {
        private String username;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
