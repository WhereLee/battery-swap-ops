package com.swapops.server.user.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import org.springframework.security.access.prepost.PreAuthorize;

import com.swapops.server.common.Result;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.user.entity.SwapUserEntity;
import com.swapops.server.user.service.UserAccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户管理（S4.5 批二，鉴权由 AdminTokenFilter 把关）：查询 + 启停用（即时生效）。
 */
@RestController
@RequestMapping("admin/user")
public class AdminUserController {

    private final UserAccountService userAccountService;

    public AdminUserController(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @GetMapping
        @PreAuthorize("hasAuthority('admin:user:read')")
    public Result<PageResult<SwapUserEntity>> page(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer limit,
                                                   @RequestParam(required = false) String phone) {
        return Result.ok(userAccountService.adminPage(page, limit, phone));
    }

    @PostMapping("/{id}/status")
        @PreAuthorize("hasAuthority('admin:user:manage')")
    @AdminLog("USER_STATUS")
    public Result<SwapUserEntity> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        return Result.ok(userAccountService.changeStatus(id, status));
    }
}
