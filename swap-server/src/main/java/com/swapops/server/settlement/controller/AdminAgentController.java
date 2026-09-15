package com.swapops.server.settlement.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.common.Result;
import com.swapops.server.settlement.entity.AgentEntity;
import com.swapops.server.settlement.form.AgentForm;
import com.swapops.server.settlement.service.AgentService;
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
 * 管理端代理商（S7 WP-B）：创建/编辑/启停/列表（admin:agent-mgmt:*）。
 */
@RestController
@RequestMapping("admin/agent")
public class AdminAgentController {

    private final AgentService agentService;

    public AdminAgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + AdminRole.AGENT_MGMT_READ + "')")
    public Result<List<AgentEntity>> list() {
        return Result.ok(agentService.list());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + AdminRole.AGENT_MGMT_MANAGE + "')")
    @AdminLog("AGENT_CREATE")
    public Result<AgentEntity> create(@RequestBody AgentForm form) {
        return Result.ok(agentService.create(form.getAgentNo(), form.getName(), form.getContact(),
                form.getShareBp(), form.getSettlementCycle()));
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('" + AdminRole.AGENT_MGMT_MANAGE + "')")
    @AdminLog("AGENT_UPDATE")
    public Result<AgentEntity> update(@PathVariable Long id, @RequestBody AgentForm form) {
        return Result.ok(agentService.update(id, form.getName(), form.getContact(),
                form.getShareBp(), form.getSettlementCycle()));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('" + AdminRole.AGENT_MGMT_MANAGE + "')")
    @AdminLog("AGENT_STATUS")
    public Result<AgentEntity> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        return Result.ok(agentService.changeStatus(id, status));
    }
}
