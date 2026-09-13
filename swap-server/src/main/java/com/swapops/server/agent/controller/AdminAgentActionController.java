package com.swapops.server.agent.controller;

import com.swapops.server.agent.entity.AgentActionEntity;
import com.swapops.server.agent.form.AgentActionForm;
import com.swapops.server.agent.service.AgentActionService;
import com.swapops.server.common.Result;
import com.swapops.server.common.utils.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 动作接缝（S4.6，鉴权由 AdminTokenFilter 把关）：
 * 建议（POST，幂等键可选）→ 人工确认（confirm，真正执行）/驳回（reject）；列表即审计。
 */
@RestController
@RequestMapping("admin/agent-action")
public class AdminAgentActionController {

    private static final String IDEM_HEADER = "Idempotency-Key";

    private final AgentActionService agentActionService;

    public AdminAgentActionController(AgentActionService agentActionService) {
        this.agentActionService = agentActionService;
    }

    @PostMapping
    public Result<AgentActionEntity> propose(@RequestBody AgentActionForm form,
                                             @RequestHeader(value = IDEM_HEADER, required = false) String idemKey) {
        return Result.ok(agentActionService.propose(form, idemKey));
    }

    @GetMapping
    public Result<PageResult<AgentActionEntity>> page(@RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer limit,
                                                      @RequestParam(required = false) Integer status) {
        return Result.ok(agentActionService.page(page, limit, status));
    }

    @GetMapping("/{id}")
    public Result<AgentActionEntity> detail(@PathVariable Long id) {
        return Result.ok(agentActionService.require(id));
    }

    @PostMapping("/{id}/confirm")
    public Result<AgentActionEntity> confirm(@PathVariable Long id,
                                             @RequestParam(required = false, defaultValue = "admin") String confirmer) {
        return Result.ok(agentActionService.confirm(id, confirmer));
    }

    @PostMapping("/{id}/reject")
    public Result<AgentActionEntity> reject(@PathVariable Long id,
                                            @RequestParam(required = false, defaultValue = "admin") String confirmer,
                                            @RequestParam(required = false) String remark) {
        return Result.ok(agentActionService.reject(id, confirmer, remark));
    }
}
