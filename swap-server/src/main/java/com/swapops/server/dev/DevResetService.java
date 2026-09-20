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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联调数据重置（S4-pre，仅 swap.dev.enabled=true 生效）：
 * 把"设备侧事实"恢复为可复跑状态——活跃订单取消、电池全部归位/停用、分配池重建。
 * 幂等：重复执行结果一致；不做资金回滚（联调语义，钱包/套餐保持不动）。
 *
 * <p>收敛性设计（批次18）："清场→绑定"两阶段——先全量脱仓/清引用，再按种子定义统一重绑；
 * 输出只依赖种子定义与输入状态无关（旧实现"逐仓边扫边脱+绑"在交错引用图上不收敛：
 * 后序仓的 detach 会打掉前序仓刚建立的一致绑定，残留集合随输入漂移）。
 * 种子电池缺失时对应仓保持空仓并记入报告（旧实现静默 continue，孤儿引用永不修复）。</p>
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
    private final com.swapops.server.settlement.dao.SettlementStatementDao settlementStatementDao;
    private final com.swapops.server.settlement.dao.OrderSettlementDao orderSettlementDao;

    public DevResetService(SwapOrderDao orderDao, CabinetDao cabinetDao, CellDao cellDao,
                           BatteryDao batteryDao, StringRedisTemplate stringRedisTemplate,
                           AllocationService allocationService, DevProperties devProperties,
                           WalletDao walletDao, UserPlanDao userPlanDao, SwapUserDao swapUserDao,
                           com.swapops.server.order.dao.ArrearsRecordDao arrearsRecordDao,
                           com.swapops.server.user.dao.UserCouponDao userCouponDao,
                           com.swapops.server.user.dao.CouponTemplateDao couponTemplateDao,
                           com.swapops.server.user.dao.UserMessageDao userMessageDao,
                           com.swapops.server.settlement.dao.SettlementStatementDao settlementStatementDao,
                           com.swapops.server.settlement.dao.OrderSettlementDao orderSettlementDao) {
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
        this.settlementStatementDao = settlementStatementDao;
        this.orderSettlementDao = orderSettlementDao;
    }

    @Transactional
    public Map<String, Object> reset() {
        long now = System.currentTimeMillis();
        int cancelled = cancelActiveOrders(now);

        List<CabinetEntity> cabinets = cabinetDao.selectList(new LambdaQueryWrapper<CabinetEntity>()
                .orderByAsc(CabinetEntity::getCabinetNo));
        int cellsPerCabinet = devProperties.getCellsPerCabinet();

        // ---- 阶段一：清场（收敛的前提——先统一脱仓/清引用，再统一绑定） ----
        // ① 全部电池脱仓、清持有人、回充电态（种子电池随后回满电位）
        int detached = batteryDao.update(null, new LambdaUpdateWrapper<BatteryEntity>()
                // 批次43 补（实机探针发现）：这里**必须**有恒真条件。批次30 给 MyBatis-Plus 挂了
                // BlockAttackInnerInterceptor（防误操作全表 UPDATE/DELETE），而本语句原先只有 SET、
                // 没有 WHERE ⇒ 每次 /dev/device/reset 都抛 "Prohibition of table update operation" → 500。
                // 同文件其它全表清理（欠费/券/站内信/结算单）都写了 `.gt(id, 0)`，唯独这条漏了——
                // 也就是说"加了拦截器"这件事当年没有回归到这条路径上。
                .gt(BatteryEntity::getId, 0L)
                .set(BatteryEntity::getCellId, null)
                .set(BatteryEntity::getHolderUserId, null)
                .set(BatteryEntity::getStatus, BatteryStatus.CHARGING.getCode())
                .set(BatteryEntity::getUpdateTime, now));
        // ② 全部柜仓清引用并删仓锁（含种子仓旧引用——旧实现"边扫边脱+绑"在交错引用图上不收敛，
        //    后序仓的 detach 会打掉前序仓刚建立的一致绑定；全量清场后重绑与输入状态无关）
        Map<String, CellEntity> cellIndex = new LinkedHashMap<>();
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
                cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                        .eq(CellEntity::getId, cell.getId())
                        .set(CellEntity::getBatteryId, null)
                        .set(CellEntity::getStatus, CellStatus.EMPTY.getCode())
                        .set(CellEntity::getLockOrderId, null)
                        .set(CellEntity::getUpdateTime, now));
                cellIndex.put(i + ":" + j, cell);
            }
        }

        // ---- 阶段二：绑定种子（只依赖种子定义，重跑收敛） ----
        int occupied = 0;
        List<String> seedMissing = new ArrayList<>();
        for (int i = 0; i < cabinets.size(); i++) {
            CabinetEntity cabinet = cabinets.get(i);
            for (int j = 1; j <= devProperties.getFullCells(); j++) {
                CellEntity cell = cellIndex.get(i + ":" + j);
                if (cell == null) {
                    continue;
                }
                String batteryNo = String.format("BAT-%04d", i * cellsPerCabinet + j);
                BatteryEntity battery = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                        .eq(BatteryEntity::getBatteryNo, batteryNo));
                if (battery == null) {
                    // 种子电池缺失（历史柜/未建种子）：该仓保持空仓并入报告——旧实现此处静默 continue，
                    // 导致旧引用永不修复（孤儿柜残留根因之一）
                    seedMissing.add(batteryNo + "@" + cabinet.getCabinetNo() + "#" + j);
                    continue;
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
            }
        }
        if (!seedMissing.isEmpty()) {
            log.warn("[dev-reset] 种子电池缺失 {} 处（对应仓保持空仓；补种子或退役该柜前 reset 无法将其置满）: {}",
                    seedMissing.size(), seedMissing);
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
        // S7 WP-B：清结算单并释放挂单（分账流水保留——完成单的历史分账是事实，避免对账⑭误报）
        int statementsCleared = settlementStatementDao.delete(
                new LambdaQueryWrapper<com.swapops.server.settlement.entity.SettlementStatementEntity>()
                        .gt(com.swapops.server.settlement.entity.SettlementStatementEntity::getId, 0));
        int unlinked = orderSettlementDao.update(null, new LambdaUpdateWrapper<com.swapops.server.settlement.entity.OrderSettlementEntity>()
                .isNotNull(com.swapops.server.settlement.entity.OrderSettlementEntity::getStatementId)
                .set(com.swapops.server.settlement.entity.OrderSettlementEntity::getStatementId, null));
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
        result.put("batteriesDetached", detached);
        result.put("seedMissing", seedMissing);
        result.put("walletsReset", walletsReset);
        result.put("plansReset", plansReset);
        result.put("arrearsCleared", arrearsCleared);
        result.put("couponsCleared", couponsCleared);
        result.put("messagesCleared", messagesCleared);
        result.put("statementsCleared", statementsCleared);
        result.put("statementsUnlinked", unlinked);
        log.warn("[dev-reset] 联调数据已重置 cabinets={} ordersCancelled={} cellsOccupied={} batteriesDetached={} "
                        + "seedMissing={} walletsReset={} plansReset={}",
                cabinets.size(), cancelled, occupied, detached, seedMissing.size(), walletsReset, plansReset);
        return result;
    }

    private int resetWallets(long now) {
        int rows = walletDao.update(null, new LambdaUpdateWrapper<WalletEntity>()
                // 同 ①：全表重置钱包也要有恒真条件，否则被 BlockAttackInnerInterceptor 拦下（批次43 补实测）。
                .gt(WalletEntity::getUserId, 0L)
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
                // 同 ①：全表重置套餐也要恒真条件（批次43 补，全仓扫描后确认这是最后一处无谓词语句）。
                .gt(UserPlanEntity::getId, 0L)
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
