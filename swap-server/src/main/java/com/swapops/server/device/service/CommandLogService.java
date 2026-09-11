package com.swapops.server.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.contract.CommandAction;
import com.swapops.contract.CommandStatus;
import com.swapops.server.common.RRException;
import com.swapops.server.device.config.SwapRedisKeys;
import com.swapops.server.device.dao.CommandLogDao;
import com.swapops.server.device.entity.CommandLogEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 指令流水：Redis INCR 生成柜内单调 seq（跨重启持久）；状态跃迁全部 CAS（from 守卫），
 * 事件按 (cabinetNo,seq) 精确销账——沿用范例语义。
 */
@Slf4j
@Service
public class CommandLogService {

    private final CommandLogDao commandLogDao;
    private final StringRedisTemplate stringRedisTemplate;

    public CommandLogService(CommandLogDao commandLogDao, StringRedisTemplate stringRedisTemplate) {
        this.commandLogDao = commandLogDao;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 柜内单调指令序号（Redis INCR；不可用即快速失败——宁可不发也不发重） */
    public long nextSeq(String cabinetNo) {
        Long seq = stringRedisTemplate.opsForValue().increment(SwapRedisKeys.CMD_SEQ_PREFIX + cabinetNo);
        if (seq == null) {
            throw new RRException("指令序号生成失败(Redis 不可用): " + cabinetNo);
        }
        return seq;
    }

    public CommandLogEntity recordPending(String cabinetNo, CommandAction action, long seq, String traceId) {
        CommandLogEntity entity = new CommandLogEntity();
        entity.setCabinetNo(cabinetNo);
        entity.setCommandAction(action.name());
        entity.setCommandSeq(seq);
        entity.setCommandStatus(CommandStatus.PENDING.getCode());
        entity.setRetryCount(0);
        entity.setTraceId(traceId);
        long now = System.currentTimeMillis();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        commandLogDao.insert(entity);
        return entity;
    }

    /** 按 (柜,seq,动作,PENDING) 精确销账（CAS；未命中=已销/终态，幂等） */
    public boolean markArrivedBySeq(String cabinetNo, long seq, CommandAction action) {
        boolean arrived = commandLogDao.update(null, new LambdaUpdateWrapper<CommandLogEntity>()
                .eq(CommandLogEntity::getCabinetNo, cabinetNo)
                .eq(CommandLogEntity::getCommandSeq, seq)
                .eq(CommandLogEntity::getCommandAction, action.name())
                .eq(CommandLogEntity::getCommandStatus, CommandStatus.PENDING.getCode())
                .set(CommandLogEntity::getCommandStatus, CommandStatus.ARRIVED.getCode())
                .set(CommandLogEntity::getUpdateTime, System.currentTimeMillis())) > 0;
        if (arrived) {
            log.info("指令按 seq 销账 cabinetNo={} action={} seq={}", cabinetNo, action, seq);
        } else {
            log.debug("销账未命中（已闭环/非指令驱动） cabinetNo={} action={} seq={}", cabinetNo, action, seq);
        }
        return arrived;
    }

    public void markSendFailed(Long commandId) {
        cas(commandId, CommandStatus.PENDING, CommandStatus.SEND_FAILED);
    }

    public CommandLogEntity getByCabinetSeq(String cabinetNo, long seq) {
        return commandLogDao.selectOne(new LambdaQueryWrapper<CommandLogEntity>()
                .eq(CommandLogEntity::getCabinetNo, cabinetNo)
                .eq(CommandLogEntity::getCommandSeq, seq));
    }

    private boolean cas(Long commandId, CommandStatus from, CommandStatus to) {
        return commandLogDao.update(null, new LambdaUpdateWrapper<CommandLogEntity>()
                .eq(CommandLogEntity::getId, commandId)
                .eq(CommandLogEntity::getCommandStatus, from.getCode())
                .set(CommandLogEntity::getCommandStatus, to.getCode())
                .set(CommandLogEntity::getUpdateTime, System.currentTimeMillis())) > 0;
    }
}
