package com.swapops.server.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.contract.CommandAction;
import com.swapops.contract.CommandStatus;
import com.swapops.server.common.RRException;
import com.swapops.server.device.config.SwapRedisKeys;
import com.swapops.server.device.dao.CommandLogDao;
import com.swapops.server.device.entity.CommandLogEntity;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 指令流水：Redis INCR 生成柜内单调 seq（跨重启持久）；状态跃迁全部 CAS（from 守卫），
 * 事件按 (cabinetNo,seq) 精确销账——沿用范例语义。
 */
@Slf4j
@Service
public class CommandLogService {

    /**
     * 启动对齐脚本：GET 现值比对 DB MAX，仅当 key 缺失或现值更小时 SET——LUA 原子
     */
    private static final RedisScript<Long> ALIGN_SEQ_SCRIPT = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[1]) "
                    + "if not cur or tonumber(cur) < tonumber(ARGV[1]) then "
                    + "redis.call('SET', KEYS[1], ARGV[1]) "
                    + "return 1 end "
                    + "return 0",
            Long.class);

    private final CommandLogDao commandLogDao;
    private final StringRedisTemplate stringRedisTemplate;

    public CommandLogService(CommandLogDao commandLogDao, StringRedisTemplate stringRedisTemplate) {
        this.commandLogDao = commandLogDao;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 启动播种/对齐：Redis 数据丢失/回退后 INCR 从 1 重来会撞 uk_cabinet_seq——
     * 把每柜 seq 对齐为 max(Redis 现值, DB 历史最大值)，单调性跨 Redis 丢失仍成立（沿用范例）。
     * 对齐失败不阻断启动（尽力恢复而非启动强依赖）。
     */
    @PostConstruct
    public void seedSeqFromDb() {
        try {
            List<Map<String, Object>> rows = commandLogDao.selectMaps(new QueryWrapper<CommandLogEntity>()
                    .select("cabinet_no", "MAX(command_seq) AS max_seq")
                    .groupBy("cabinet_no"));
            for (Map<String, Object> row : rows) {
                Object cabinetNo = row.get("cabinet_no");
                Object maxSeq = row.get("max_seq");
                if (cabinetNo != null && maxSeq != null) {
                    Long aligned = stringRedisTemplate.execute(ALIGN_SEQ_SCRIPT,
                            Collections.singletonList(SwapRedisKeys.CMD_SEQ_PREFIX + cabinetNo),
                            String.valueOf(maxSeq));
                    if (aligned != null && aligned == 1L) {
                        log.info("指令序号对齐 cabinetNo={} seq={}（DB 历史最大值；Redis 缺失或落后已校正）",
                                cabinetNo, maxSeq);
                    }
                }
            }
        } catch (Exception e) {
            log.error("指令序号对齐失败（不阻断启动；Redis 数据丢失场景下首次下发可能撞唯一索引）", e);
        }
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

    /** 超时待对账指令（按创建时间升序，单轮限量） */
    public List<CommandLogEntity> findTimeoutPending(int timeoutSeconds, int limit) {
        long deadline = System.currentTimeMillis() - timeoutSeconds * 1000L;
        return commandLogDao.selectList(new LambdaQueryWrapper<CommandLogEntity>()
                .eq(CommandLogEntity::getCommandStatus, CommandStatus.PENDING.getCode())
                .lt(CommandLogEntity::getCreateTime, deadline)
                .orderByAsc(CommandLogEntity::getCreateTime)
                .last("LIMIT " + limit));
    }

    /** 重试计数 +1（PENDING 守卫：已终态的流水不再计数） */
    public void markRetried(Long commandId) {
        commandLogDao.update(null, new LambdaUpdateWrapper<CommandLogEntity>()
                .eq(CommandLogEntity::getId, commandId)
                .eq(CommandLogEntity::getCommandStatus, CommandStatus.PENDING.getCode())
                .setSql("retry_count = retry_count + 1")
                .set(CommandLogEntity::getUpdateTime, System.currentTimeMillis()));
    }

    /** 重试超限定格（CAS：事件恰好到位时未命中=指令实际成功，不误告警） */
    public boolean markRetryExceeded(Long commandId) {
        return cas(commandId, CommandStatus.PENDING, CommandStatus.RETRY_EXCEEDED);
    }

    /** 被更新指令取代（同设备更晚 seq 的指令已接管） */
    public boolean markSuperseded(Long commandId) {
        return cas(commandId, CommandStatus.PENDING, CommandStatus.SUPERSEDED);
    }

    /** 设备故障中断：该柜全部在途指令显式终止（FAULT 证据，永不执行） */
    public int markExecFailedByCabinet(String cabinetNo) {
        int rows = commandLogDao.update(null, new LambdaUpdateWrapper<CommandLogEntity>()
                .eq(CommandLogEntity::getCabinetNo, cabinetNo)
                .eq(CommandLogEntity::getCommandStatus, CommandStatus.PENDING.getCode())
                .set(CommandLogEntity::getCommandStatus, CommandStatus.EXEC_FAILED.getCode())
                .set(CommandLogEntity::getUpdateTime, System.currentTimeMillis()));
        if (rows > 0) {
            log.warn("设备故障中断在途指令 cabinetNo={} rows={}", cabinetNo, rows);
        }
        return rows;
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
