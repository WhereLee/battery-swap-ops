package com.swapops.server.settlement.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.common.RRException;
import com.swapops.server.settlement.dao.AgentDao;
import com.swapops.server.settlement.entity.AgentEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 代理商管理（S7 WP-B）：分成比例万分比 0~10000；停用不影响存量结算（结算按代理维度）。
 */
@Slf4j
@Service
public class AgentService {

    private final AgentDao agentDao;

    public AgentService(AgentDao agentDao) {
        this.agentDao = agentDao;
    }

    public AgentEntity create(String agentNo, String name, String contact, Integer shareBp,
                              String settlementCycle) {
        String no = agentNo == null ? "" : agentNo.trim();
        if (!no.matches("^[A-Za-z0-9-]{2,32}$")) {
            throw new RRException("代理编号格式非法（2~32 位字母/数字/-）: " + agentNo);
        }
        if (agentDao.selectOne(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getAgentNo, no)) != null) {
            throw new RRException("代理编号已存在: " + no);
        }
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new RRException("代理名称必填且 <=64 字");
        }
        int bp = shareBp == null ? 0 : shareBp;
        if (bp < 0 || bp > 10000) {
            throw new RRException("分成比例需在 0~10000（万分比）");
        }
        String cycle = settlementCycle == null || settlementCycle.isBlank()
                ? "MONTHLY" : settlementCycle.trim().toUpperCase();
        if (!List.of("DAILY", "WEEKLY", "MONTHLY").contains(cycle)) {
            throw new RRException("结算口径可选 DAILY/WEEKLY/MONTHLY: " + settlementCycle);
        }
        long now = System.currentTimeMillis();
        AgentEntity agent = new AgentEntity();
        agent.setAgentNo(no);
        agent.setName(name.trim());
        agent.setContact(contact == null || contact.isBlank() ? null : contact.trim());
        agent.setStatus(1);
        agent.setShareBp(bp);
        agent.setSettlementCycle(cycle);
        agent.setCreateTime(now);
        agent.setUpdateTime(now);
        agentDao.insert(agent);
        log.info("[代理] 创建 id={} agentNo={} shareBp={}", agent.getId(), no, bp);
        return agent;
    }

    public AgentEntity update(Long id, String name, String contact, Integer shareBp, String settlementCycle) {
        AgentEntity agent = require(id);
        if (name != null && !name.isBlank()) {
            if (name.length() > 64) {
                throw new RRException("代理名称过长（<=64）");
            }
            agent.setName(name.trim());
        }
        if (contact != null) {
            agent.setContact(contact.isBlank() ? null : contact.trim());
        }
        if (shareBp != null) {
            if (shareBp < 0 || shareBp > 10000) {
                throw new RRException("分成比例需在 0~10000（万分比）");
            }
            agent.setShareBp(shareBp);
        }
        if (settlementCycle != null && !settlementCycle.isBlank()) {
            String cycle = settlementCycle.trim().toUpperCase();
            if (!List.of("DAILY", "WEEKLY", "MONTHLY").contains(cycle)) {
                throw new RRException("结算口径可选 DAILY/WEEKLY/MONTHLY");
            }
            agent.setSettlementCycle(cycle);
        }
        agent.setUpdateTime(System.currentTimeMillis());
        agentDao.updateById(agent);
        log.info("[代理] 更新 id={} name={} shareBp={}", id, agent.getName(), agent.getShareBp());
        return agentDao.selectById(id);
    }

    public AgentEntity changeStatus(Long id, Integer status) {
        if (status == null || (status != 1 && status != 2)) {
            throw new RRException("代理状态可选 1 启用 / 2 停用");
        }
        AgentEntity agent = require(id);
        agent.setStatus(status);
        agent.setUpdateTime(System.currentTimeMillis());
        agentDao.update(null, new LambdaUpdateWrapper<AgentEntity>()
                .eq(AgentEntity::getId, id)
                .set(AgentEntity::getStatus, status)
                .set(AgentEntity::getUpdateTime, System.currentTimeMillis()));
        log.warn("[代理] 状态变更 id={} agentNo={} status={}", id, agent.getAgentNo(), status);
        return agentDao.selectById(id);
    }

    public List<AgentEntity> list() {
        return agentDao.selectList(new LambdaQueryWrapper<AgentEntity>()
                .orderByAsc(AgentEntity::getId)
                .last("LIMIT 200"));
    }

    public AgentEntity require(Long id) {
        AgentEntity agent = agentDao.selectById(id);
        if (agent == null) {
            throw new RRException("代理不存在: " + id);
        }
        return agent;
    }
}
