package com.swapops.server.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.contract.BatteryStatus;
import com.swapops.contract.CellStatus;
import com.swapops.server.common.RRException;
import com.swapops.server.config.BillingProperties;
import com.swapops.server.device.config.SwapRedisKeys;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 分配引擎（S0.1 §6.1 / S0.5 §3）：满电电池与仓位的**并发安全分配**。
 *
 * <p>两层保障：
 * <ol>
 *   <li>Redis 集合 + LUA 原子弹仓：候选 cellId 从集合弹出并当场打 cell-lock（SET NX EX），
 *       同刻并发只有一个调用者拿到同一个候选；</li>
 *   <li>DB 条件更新兜底：`lock_order_id IS NULL` 才落锁——Redis 丢数据/时钟问题时的最终判官。</li>
 * </ol>
 * 校验（内容与登记一致）失败即回滚锁重试；集合由事件/管理态变更驱动同步，启动全量重建。</p>
 */
@Slf4j
@Service
public class AllocationService {

    /** 单次分配最多弹仓重试次数（弹到脏候选就继续弹） */
    private static final int MAX_ALLOC_TRY = 5;

    /**
     * LUA：从可分配集合弹出一个仓并打预占锁（NX EX）；弹到的候选若已被别的请求锁定则丢弃继续弹。
     * KEYS[1]=集合键；ARGV[1]=锁键前缀；ARGV[2]=锁 TTL 秒；ARGV[3]=最多尝试次数
     */
    private static final RedisScript<String> ALLOC_SCRIPT = new DefaultRedisScript<>(
            "for i = 1, tonumber(ARGV[3]) do "
                    + "local cell = redis.call('SPOP', KEYS[1]) "
                    + "if not cell then return nil end "
                    + "local lockKey = ARGV[1] .. cell "
                    + "local ok = redis.call('SET', lockKey, '1', 'NX', 'EX', tonumber(ARGV[2])) "
                    + "if ok then return cell end "
                    + "end "
                    + "return nil",
            String.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final CabinetDao cabinetDao;
    private final CellDao cellDao;
    private final BatteryDao batteryDao;
    private final BillingProperties billingProperties;

    public AllocationService(StringRedisTemplate stringRedisTemplate, CabinetDao cabinetDao,
                             CellDao cellDao, BatteryDao batteryDao, BillingProperties billingProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cabinetDao = cabinetDao;
        this.cellDao = cellDao;
        this.batteryDao = batteryDao;
        this.billingProperties = billingProperties;
    }

    /** 分配结果（full 路径带电池；empty 路径 battery 为 null） */
    public record AllocResult(CellEntity cell, BatteryEntity battery) {
    }

    /**
     * 分配一个仓（fullPath=true 选满电电池仓；false 选空仓）。
     *
     * @throws RRException 无可用资源
     */
    public AllocResult allocate(String cabinetNo, Long orderId, String orderNo, boolean fullPath) {
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo));
        if (cabinet == null) {
            throw new RRException("柜不存在: " + cabinetNo);
        }
        if (cabinet.getStatus() == null || cabinet.getStatus() > 2) {
            // 1 在线 / 2 满载 可用；3 故障 / 4 维护 / 5 停用 不可分配
            throw new RRException("柜当前不可用: " + cabinetNo + " status=" + cabinet.getStatus());
        }
        String setKey = (fullPath ? SwapRedisKeys.ALLOC_FULL_PREFIX : SwapRedisKeys.ALLOC_EMPTY_PREFIX) + cabinetNo;
        int ttlSeconds = billingProperties.getPreemptTtlSeconds();

        for (int attempt = 0; attempt < MAX_ALLOC_TRY; attempt++) {
            String cellIdStr = stringRedisTemplate.execute(ALLOC_SCRIPT, List.of(setKey),
                    SwapRedisKeys.CELL_LOCK_PREFIX, String.valueOf(ttlSeconds), String.valueOf(MAX_ALLOC_TRY));
            if (cellIdStr == null) {
                break;
            }
            Long cellId = Long.valueOf(cellIdStr);
            long now = System.currentTimeMillis();
            // DB 兜底锁：lock_order_id IS NULL 才成功
            int rows = cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                    .eq(CellEntity::getId, cellId)
                    .isNull(CellEntity::getLockOrderId)
                    .set(CellEntity::getLockOrderId, orderId)
                    .set(CellEntity::getUpdateTime, now));
            if (rows == 0) {
                stringRedisTemplate.delete(SwapRedisKeys.CELL_LOCK_PREFIX + cellId);
                continue;
            }
            CellEntity cell = cellDao.selectById(cellId);
            BatteryEntity battery = null;
            boolean valid;
            if (cell == null) {
                valid = false;
            } else if (fullPath) {
                battery = cell.getBatteryId() == null ? null : batteryDao.selectById(cell.getBatteryId());
                valid = cell.getStatus() != null && cell.getStatus() == CellStatus.OCCUPIED.getCode()
                        && battery != null && battery.getStatus() != null
                        && battery.getStatus() == BatteryStatus.FULL.getCode()
                        && cellId.equals(battery.getCellId());
            } else {
                valid = cell.getStatus() != null && cell.getStatus() == CellStatus.EMPTY.getCode()
                        && cell.getBatteryId() == null;
            }
            if (!valid) {
                log.warn("候选仓状态失效，回滚重试 cabinetNo={} cellId={} fullPath={}", cabinetNo, cellId, fullPath);
                unlockAndDeleteLock(cellId, orderId);
                continue;
            }
            stringRedisTemplate.opsForValue().set(SwapRedisKeys.PREEMPT_PREFIX + orderNo,
                    String.valueOf(cellId), ttlSeconds, TimeUnit.SECONDS);
            log.info("分配成功 orderNo={} cabinetNo={} cellNo={} batteryNo={}", orderNo, cabinetNo,
                    cell.getCellNo(), battery == null ? "-" : battery.getBatteryNo());
            return new AllocResult(cell, battery);
        }
        throw new RRException(fullPath ? "暂无可换的满电电池，请稍后重试" : "暂无可用还电仓位，请稍后重试");
    }

    /** 释放预占（取消/超时/下发失败补偿）：解锁 + 归还候选集 */
    public void release(Long cellId, Long orderId, String orderNo) {
        if (cellId != null) {
            unlockAndDeleteLock(cellId, orderId);
        }
        if (orderNo != null) {
            stringRedisTemplate.delete(SwapRedisKeys.PREEMPT_PREFIX + orderNo);
        }
    }

    /** 取电事件：仓变空——订单已推进，清锁并把仓并入 empty 集合 */
    public void onBatteryOut(Long cellId) {
        clearLockIfAny(cellId);
        refreshByCellId(cellId);
    }

    /** 还电事件：仓变占用（充电中）——清锁（若有）并刷新集合（未满电不进 full） */
    public void onBatteryIn(Long cellId) {
        clearLockIfAny(cellId);
        refreshByCellId(cellId);
    }

    /** 电量达满：仓进入可分配 full 集合 */
    public void onBatteryFull(Long cellId) {
        refreshByCellId(cellId);
    }

    /** 管理态/故障变更后刷新该仓（cellId 可空时忽略） */
    public void refreshByCellId(Long cellId) {
        if (cellId == null) {
            return;
        }
        CellEntity cell = cellDao.selectById(cellId);
        if (cell == null) {
            return;
        }
        CabinetEntity cabinet = cabinetDao.selectById(cell.getCabinetId());
        if (cabinet == null || cabinet.getCabinetNo() == null) {
            return;
        }
        syncCellInto(cabinet, cell);
    }

    /** 电池状态变更后刷新其所在仓（电池不在仓则忽略） */
    public void refreshByBatteryId(Long batteryId) {
        if (batteryId == null) {
            return;
        }
        BatteryEntity battery = batteryDao.selectById(batteryId);
        if (battery != null && battery.getCellId() != null) {
            refreshByCellId(battery.getCellId());
        }
    }

    /** 柜可用满电数（用户端站点列表用；与分配同源） */
    public long countAvailable(String cabinetNo, boolean fullPath) {
        String key = (fullPath ? SwapRedisKeys.ALLOC_FULL_PREFIX : SwapRedisKeys.ALLOC_EMPTY_PREFIX) + cabinetNo;
        Long size = stringRedisTemplate.opsForSet().size(key);
        return size == null ? 0 : size;
    }

    /**
     * 启动/数据修复：按 DB 真值全量重建可分配集合（Redis 数据丢失、或种子/管理数据变更后调用）。
     * 只重建"可分配"（未锁定、柜可用、状态匹配）——锁定中的仓不进入集合。
     */
    public void rebuildFromDb() {
        List<CabinetEntity> cabinets = cabinetDao.selectList(null);
        for (CabinetEntity cabinet : cabinets) {
            if (cabinet.getCabinetNo() == null) {
                continue;
            }
            stringRedisTemplate.delete(SwapRedisKeys.ALLOC_FULL_PREFIX + cabinet.getCabinetNo());
            stringRedisTemplate.delete(SwapRedisKeys.ALLOC_EMPTY_PREFIX + cabinet.getCabinetNo());
            List<CellEntity> cells = cellDao.selectList(new LambdaQueryWrapper<CellEntity>()
                    .eq(CellEntity::getCabinetId, cabinet.getId()));
            for (CellEntity cell : cells) {
                syncCellInto(cabinet, cell);
            }
        }
        log.info("可分配集合已重建 cabinets={}", cabinets.size());
    }

    private void syncCellInto(CabinetEntity cabinet, CellEntity cell) {
        String cabinetNo = cabinet.getCabinetNo();
        String fullKey = SwapRedisKeys.ALLOC_FULL_PREFIX + cabinetNo;
        String emptyKey = SwapRedisKeys.ALLOC_EMPTY_PREFIX + cabinetNo;
        stringRedisTemplate.opsForSet().remove(fullKey, String.valueOf(cell.getId()));
        stringRedisTemplate.opsForSet().remove(emptyKey, String.valueOf(cell.getId()));
        boolean cabinetUsable = cabinet.getStatus() != null && cabinet.getStatus() <= 2;
        if (!cabinetUsable || cell.getLockOrderId() != null) {
            return;
        }
        if (cell.getStatus() != null && cell.getStatus() == CellStatus.OCCUPIED.getCode()
                && cell.getBatteryId() != null) {
            BatteryEntity battery = batteryDao.selectById(cell.getBatteryId());
            if (battery != null && battery.getStatus() != null
                    && battery.getStatus() == BatteryStatus.FULL.getCode()
                    && cell.getId().equals(battery.getCellId())) {
                stringRedisTemplate.opsForSet().add(fullKey, String.valueOf(cell.getId()));
            }
        } else if (cell.getStatus() != null && cell.getStatus() == CellStatus.EMPTY.getCode()) {
            stringRedisTemplate.opsForSet().add(emptyKey, String.valueOf(cell.getId()));
        }
    }

    /** 条件解锁（仅 orderId 持锁时）并删 Redis 锁 */
    private void unlockAndDeleteLock(Long cellId, Long orderId) {
        cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                .eq(CellEntity::getId, cellId)
                .eq(CellEntity::getLockOrderId, orderId)
                .set(CellEntity::getLockOrderId, null)
                .set(CellEntity::getUpdateTime, System.currentTimeMillis()));
        stringRedisTemplate.delete(SwapRedisKeys.CELL_LOCK_PREFIX + cellId);
        refreshByCellId(cellId);
    }

    /** 无条件清锁（取/还电后订单已推进，锁必清） */
    private void clearLockIfAny(Long cellId) {
        cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                .eq(CellEntity::getId, cellId)
                .isNotNull(CellEntity::getLockOrderId)
                .set(CellEntity::getLockOrderId, null)
                .set(CellEntity::getUpdateTime, System.currentTimeMillis()));
        stringRedisTemplate.delete(SwapRedisKeys.CELL_LOCK_PREFIX + cellId);
    }
}
