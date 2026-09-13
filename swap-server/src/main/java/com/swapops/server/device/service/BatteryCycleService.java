package com.swapops.server.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.device.dao.BatteryCycleLogDao;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.entity.BatteryCycleLogEntity;
import com.swapops.server.device.entity.BatteryEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 电池循环计数（S4.1，可审计）：
 * 先写流水（唯一键 (bootId,eventSeq) 幂等）→ 成功才原子累加计数器。
 * 口径：OUT → swaps+1（取出/换电服务次数）；IN → cycle_count+1（归仓服务循环）。
 * 说明：cycle_count 为"服务循环"口径；BMS 等效循环（Ah/DOD 折算）需设备侧明细，留演进（文档声明）。
 */
@Slf4j
@Service
public class BatteryCycleService {

    public static final String ACTION_OUT = "OUT";
    public static final String ACTION_IN = "IN";

    private final BatteryCycleLogDao cycleLogDao;
    private final BatteryDao batteryDao;

    public BatteryCycleService(BatteryCycleLogDao cycleLogDao, BatteryDao batteryDao) {
        this.cycleLogDao = cycleLogDao;
        this.batteryDao = batteryDao;
    }

    /**
     * 记录一次循环事件并累加计数器（幂等：重复 (bootId,eventSeq) 直接忽略）。
     *
     * @return true=首次记录并已计数；false=重复事件
     */
    public boolean record(String batteryNo, String action, Integer soc, String cabinetNo,
                          Long commandSeq, String bootId, Long eventSeq) {
        BatteryCycleLogEntity logEntity = new BatteryCycleLogEntity();
        logEntity.setBatteryNo(batteryNo);
        logEntity.setAction(action);
        logEntity.setSoc(soc);
        logEntity.setCabinetNo(cabinetNo);
        logEntity.setCommandSeq(commandSeq);
        logEntity.setBootId(bootId);
        logEntity.setEventSeq(eventSeq);
        logEntity.setCreateTime(System.currentTimeMillis());
        try {
            cycleLogDao.insert(logEntity);
        } catch (DuplicateKeyException e) {
            log.debug("[cycle] 重复事件忽略 batteryNo={} action={} bootId={} seq={}",
                    batteryNo, action, bootId, eventSeq);
            return false;
        }
        String column = ACTION_OUT.equals(action) ? "swaps" : "cycle_count";
        batteryDao.update(null, new LambdaUpdateWrapper<BatteryEntity>()
                .eq(BatteryEntity::getBatteryNo, batteryNo)
                .setSql(column + " = IFNULL(" + column + ", 0) + 1")
                .set(BatteryEntity::getUpdateTime, System.currentTimeMillis()));
        log.info("[cycle] 计数 batteryNo={} action={} +1 {} soc={}", batteryNo, action, column, soc);
        return true;
    }

    /** 某电池最近流水（健康档案审计用） */
    public List<BatteryCycleLogEntity> recentLogs(String batteryNo, int limit) {
        return cycleLogDao.selectList(new LambdaQueryWrapper<BatteryCycleLogEntity>()
                .eq(BatteryCycleLogEntity::getBatteryNo, batteryNo)
                .orderByDesc(BatteryCycleLogEntity::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }

    /** 某电池指定动作流水数（对账用） */
    public long countByAction(String batteryNo, String action) {
        Long count = cycleLogDao.selectCount(new LambdaQueryWrapper<BatteryCycleLogEntity>()
                .eq(BatteryCycleLogEntity::getBatteryNo, batteryNo)
                .eq(BatteryCycleLogEntity::getAction, action));
        return count == null ? 0 : count;
    }
}
