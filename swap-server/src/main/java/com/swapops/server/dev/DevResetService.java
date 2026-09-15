package com.swapops.server.dev;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.contract.BatteryStatus;
import com.swapops.contract.CellStatus;
import com.swapops.contract.OrderStatus;
import com.swapops.server.device.config.SwapRedisKeys;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.AllocationService;
import com.swapops.server.user.dao.SwapUserDao;
import com.swapops.server.user.dao.UserPlanDao;
import com.swapops.server.user.dao.WalletDao;
import com.swapops.server.user.entity.SwapUserEntity;
import com.swapops.server.user.entity.UserPlanEntity;
import com.swapops.server.user.entity.WalletEntity;
import com.swapops.server.user.enums.UserPlanStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 联调数据重置（S4-pre，仅 swap.dev.enabled=true 生效）：
 * 把"设备侧事实"恢复为可复跑状态——活跃订单取消、电池全部归位/停用、分配池重建。
 * 幂等：重复执行结果一致；不做资金回滚（联调语义，钱包/套餐保持不动）。
 */
@Slf4j
@Service
public class DevResetService {

    private static final List<Integer> ACTIVE_STATUSES = List.of(
            OrderStatus.PENDING_OPEN.getCode(), OrderStatus.OPENED.getCode(),
            OrderStatus.TAKEN.getCode(), OrderStatus.OVERDUE.getCode());

    private final SwapOrderDao orderDao;
    private final CabinetDao cabinetDao;
    private final CellDao cellDao;
    private final BatteryDao batteryDao;
    private final StringRedisTemplate stringRedisTemplate;
    private final AllocationService allocationService;
    private final DevProperties devProperties;
    private final WalletDao walletDao;
    private final UserPlanDao userPlanDao;
    private final SwapUserDao swapUserDao;
    private final com.swapops.server.order.dao.ArrearsRecordDao arrearsRecordDao;
    private final com.swapops.server.user.dao.UserCouponDao userCouponDao;
    private final com.swapops.server.user.dao.CouponTemplateDao couponTemplateDao;
    private final com.swapops.server.user.dao.UserMessageDao userMessageDao;

    public DevResetService(SwapOrderDao orderDao, CabinetDao cabinetDao, CellDao cellDao,
                           BatteryDao batteryDao, StringRedisTemplate stringRedisTemplate,
                           AllocationService allocationService, DevProperties devProperties,
                           WalletDao walletDao, UserPlanDao userPlanDao, SwapUserDao swapUserDao,
                           com.swapops.server.order.dao.ArrearsRecordDao arrearsRecordDao,
                           com.swapops.server.user.dao.UserCouponDao userCouponDao,
                           com.swapops.server.user.dao.CouponTemplateDao couponTemplateDao,
                           com.swapops.server.user.dao.UserMessageDao userMessageDao) {
        this.orderDao = orderDao;
        this.cabinetDao = cabinetDao;
        this.cellDao = cellDao;
        this.batteryDao = batteryDao;
        this.stringRedisTemplate = stringRedisTemplate;
        this.allocationService = allocationService;
        this.devProperties = devProperties;
        this.walletDao = walletDao;
        this.userPlanDao = userPlanDao;
        this.swapUserDao = swapUserDao;
        this.arrearsRecordDao = arrearsRecordDao;
        this.userCouponDao = userCouponDao;
        this.couponTemplateDao = couponTemplateDao;
        this.userMessageDao = userMessageDao;
    }

    @Transactional
    public Map<String, Object> reset() {
        long now = System.currentTimeMillis();
        int cancelled = cancelActiveOrders(now);
        int occupied = 0;
        int parked = 0;
        Set<Long> seededBatteryIds = new HashSet<>();

        List<CabinetEntity> cabinets = cabinetDao.selectList(new LambdaQueryWrapper<CabinetEntity>()
                .orderByAsc(CabinetEntity::getCabinetNo));
        int cellsPerCabinet = devProperties.getCellsPerCabinet();
        for (int i = 0; i < cabinets.size(); i++) {
            CabinetEntity cabinet = cabinets.get(i);
            for (int j = 1; j <= cellsPerCabinet; j++) {
                CellEntity cell = cellDao.selectOne(new LambdaQueryWrapper<CellEntity>()
                        .eq(CellEntity::getCabinetId, cabinet.getId())
                        .eq(CellEntity::getCellNo, j));
                if (cell == null) {
                    continue;
                }
                stringRedisTemplate.delete(SwapRedisKeys.CELL_LOCK_PREFIX + cell.getId());
                if (j <= devProperties.getFullCells()) {
                    String batteryNo = String.format("BAT-%04d", i * cellsPerCabinet + j);
                    BatteryEntity battery = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                            .eq(BatteryEntity::getBatteryNo, batteryNo));
                    if (battery == null) {
                        continue;
                    }
                    seededBatteryIds.add(battery.getId());
                    // S5 修复：先脱旧占位（换电剧本残留旧电池占种子仓时，直接绑种子会撞 uk_battery_cell 唯一键）
                    if (cell.getBatteryId() != null && !cell.getBatteryId().equals(battery.getId())) {
                        detachBattery(cell.getBatteryId(), now);
                    }
                    batteryDao.update(null, new LambdaUpdateWrapper<BatteryEntity>()
                            .eq(BatteryEntity::getId, battery.getId())
                            .set(BatteryEntity::getCellId, cell.getId())
                            .set(BatteryEntity::getHolderUserId, null)
                            .set(BatteryEntity::getStatus, BatteryStatus.FULL.getCode())
                            .set(BatteryEntity::getSoc, 100)
                            .set(BatteryEntity::getUpdateTime, now));
                    cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                            .eq(CellEntity::getId, cell.getId())
                            .set(CellEntity::getBatteryId, battery.getId())
                            .set(CellEntity::getStatus, CellStatus.OCCUPIED.getCode())
                            .set(CellEntity::getLockOrderId, null)
                            .set(CellEntity::getUpdateTime, now));
                    occupied++;
                } else {
                    // 空仓分支同样先脱旧占位（防仓空而旧电池 cell_id 悬空 → 对账②误报）
                    if (cell.getBatteryId() != null) {
                        detachBattery(cell.getBatteryId(), now);
                    }
                    cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                            .eq(CellEntity::getId, cell.getId())
                            .set(CellEntity::getBatteryId, null)
                            .set(CellEntity::getStatus, CellStatus.EMPTY.getCode())
                            .set(CellEntity::getLockOrderId, null)
                            .set(CellEntity::getUpdateTime, now));
                }
            }
        }

        // 非种子电池（历史剧本产物/在途）：脱仓、清持有人、置充电态
        List<BatteryEntity> extras = batteryDao.selectList(new LambdaQueryWrapper<BatteryEntity>()
                .notIn(!seededBatteryIds.isEmpty(), BatteryEntity::getId, seededBatteryIds));
        for (BatteryEntity extra : extras) {
            detachBattery(extra.getId(), now);
            parked++;
        }

        // 资金/套餐恢复播种基线：钱包余额 20000（首个用户押金 0，其余 9900）；次卡剩 5 次
        int walletsReset = resetWallets(now);
        int plansReset = resetPlans(now);

        // S7 WP-D：用户服务域联调清理（欠费/券/站内信；券模板保留、发行计数回退）
        int arrearsCleared = arrearsRecordDao.delete(new LambdaQueryWrapper<com.swapops.server.order.entity.ArrearsRecordEntity>()
                .gt(com.swapops.server.order.entity.ArrearsRecordEntity::getId, 0));
        int couponsCleared = userCouponDao.delete(new LambdaQueryWrapper<com.swapops.server.user.entity.UserCouponEntity>()
                .gt(com.swapops.server.user.entity.UserCouponEntity::getId, 0));
        couponTemplateDao.update(null, new LambdaUpdateWrapper<com.swapops.server.user.entity.CouponTemplateEntity>()
                .gt(com.swapops.server.user.entity.CouponTemplateEntity::getId, 0)
                .set(com.swapops.server.user.entity.CouponTemplateEntity::getIssuedCount, 0));
        int messagesCleared = userMessageDao.delete(new LambdaQueryWrapper<com.swapops.server.user.entity.UserMessageEntity>()
                .gt(com.swapops.server.user.entity.UserMessageEntity::getId, 0));
        // 报障去重键（S7 WP-D）：联调复跑需清窗（否则近重返回上轮工单）
        try {
            java.util.Set<String> dedupKeys =
                    stringRedisTemplate.keys(SwapRedisKeys.USER_REPORT_DEDUP_PREFIX + "*");
            if (dedupKeys != null && !dedupKeys.isEmpty()) {
                stringRedisTemplate.delete(dedupKeys);
            }
        } catch (RuntimeException e) {
            log.warn("[dev-reset] 报障去重键清理异常（忽略）: {}", e.getMessage());
        }

        allocationService.rebuildFromDb();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cabinets", cabinets.size());
        result.put("ordersCancelled", cancelled);
        result.put("cellsOccupied", occupied);
        result.put("extrasParked", parked);
        result.put("walletsReset", walletsReset);
        result.put("plansReset", plansReset);
        result.put("arrearsCleared", arrearsCleared);
        result.put("couponsCleared", couponsCleared);
        result.put("messagesCleared", messagesCleared);
        log.warn("[dev-reset] 联调数据已重置 cabinets={} ordersCancelled={} cellsOccupied={} extrasParked={} "
                        + "walletsReset={} plansReset={}",
                cabinets.size(), cancelled, occupied, parked, walletsReset, plansReset);
        return result;
    }

    /** 电池脱仓：置充电态、清持有人（重置/清理旧占位共用） */
    private void detachBattery(Long batteryId, long now) {
        batteryDao.update(null, new LambdaUpdateWrapper<BatteryEntity>()
                .eq(BatteryEntity::getId, batteryId)
                .set(BatteryEntity::getCellId, null)
                .set(BatteryEntity::getHolderUserId, null)
                .set(BatteryEntity::getStatus, BatteryStatus.CHARGING.getCode())
                .set(BatteryEntity::getUpdateTime, now));
    }

    private int resetWallets(long now) {
        int rows = walletDao.update(null, new LambdaUpdateWrapper<WalletEntity>()
                .set(WalletEntity::getBalanceFen, 20000)
                .set(WalletEntity::getDepositFen, 9900)
                .set(WalletEntity::getUpdateTime, now));
        SwapUserEntity first = swapUserDao.selectOne(new LambdaQueryWrapper<SwapUserEntity>()
                .orderByAsc(SwapUserEntity::getId)
                .last("LIMIT 1"));
        if (first != null) {
            walletDao.update(null, new LambdaUpdateWrapper<WalletEntity>()
                    .eq(WalletEntity::getUserId, first.getId())
                    .set(WalletEntity::getDepositFen, 0)
                    .set(WalletEntity::getUpdateTime, now));
        }
        return rows;
    }

    private int resetPlans(long now) {
        return userPlanDao.update(null, new LambdaUpdateWrapper<UserPlanEntity>()
                .set(UserPlanEntity::getRemainingTimes, 5)
                .set(UserPlanEntity::getStatus, UserPlanStatus.ACTIVE.getCode())
                .set(UserPlanEntity::getEndTime, now + 365L * 24 * 3600 * 1000)
                .set(UserPlanEntity::getUpdateTime, now));
    }

    private int cancelActiveOrders(long now) {
        List<SwapOrderEntity> actives = orderDao.selectList(new LambdaQueryWrapper<SwapOrderEntity>()
                .in(SwapOrderEntity::getStatus, ACTIVE_STATUSES));
        int cancelled = 0;
        for (SwapOrderEntity order : actives) {
            int rows = orderDao.update(null, new LambdaUpdateWrapper<SwapOrderEntity>()
                    .eq(SwapOrderEntity::getId, order.getId())
                    .eq(SwapOrderEntity::getStatus, order.getStatus())
                    .set(SwapOrderEntity::getStatus, OrderStatus.CANCELLED.getCode())
                    .set(SwapOrderEntity::getCancelTime, now)
                    .set(SwapOrderEntity::getCloseReason, "DEV_RESET")
                    .set(SwapOrderEntity::getUpdateTime, now));
            if (rows > 0) {
                cancelled++;
                stringRedisTemplate.delete(SwapRedisKeys.PREEMPT_PREFIX + order.getOrderNo());
            }
        }
        return cancelled;
    }
}
