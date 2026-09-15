package com.swapops.server.admin.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.entity.AdminOpLogEntity;
import com.swapops.server.admin.entity.AdminUserEntity;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.service.AdminAccountService;
import com.swapops.server.common.Result;
import com.swapops.server.common.utils.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端账号与审计（S7 WP-A，鉴权由 Security 链 + @PreAuthorize 把关）：
 * 创建/列表/启停管理员 + 审计日志查询（admin:admin:manage）。
 */
@RestController
@RequestMapping("admin/account")
public class AdminAccountController {

    private final AdminAccountService adminAccountService;

    public AdminAccountController(AdminAccountService adminAccountService) {
        this.adminAccountService = adminAccountService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + AdminRole.ADMIN_MANAGE + "')")
    public Result<List<AdminUserEntity>> list() {
        return Result.ok(adminAccountService.list());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + AdminRole.ADMIN_MANAGE + "')")
    @AdminLog("ADMIN_CREATE")
    public Result<AdminUserEntity> create(@RequestBody AdminCreateForm form) {
        return Result.ok(adminAccountService.create(form.getUsername(), form.getPassword(),
                form.getRealName(), form.getRole()));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('" + AdminRole.ADMIN_MANAGE + "')")
    @AdminLog("ADMIN_STATUS")
    public Result<AdminUserEntity> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        return Result.ok(adminAccountService.changeStatus(id, status));
    }

    @GetMapping("/op-log")
    @PreAuthorize("hasAuthority('" + AdminRole.ADMIN_MANAGE + "')")
    public Result<PageResult<AdminOpLogEntity>> opLog(@RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer limit,
                                                      @RequestParam(required = false) Long adminId,
                                                      @RequestParam(required = false) String action) {
        return Result.ok(adminAccountService.pageLogs(page, limit, adminId, action));
    }

    /** 创建表单（仅本控制器使用；密码不落响应） */
    public static class AdminCreateForm {
        private String username;
        private String password;
        private String realName;
        private String role;

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

        public String getRealName() {
            return realName;
        }

        public void setRealName(String realName) {
            this.realName = realName;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }
}
