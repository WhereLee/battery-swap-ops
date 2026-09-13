package com.swapops.server.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.swapops.contract.BatteryStatus;
import com.swapops.contract.OrderStatus;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.config.BillingProperties;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.dao.CommandLogDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import com.swapops.server.device.service.BatteryCycleService;
import com.swapops.server.transfer.dao.TransferTaskDao;
import com.swapops.server.transfer.dao.TransferTaskItemDao;
import com.swapops.server.transfer.entity.TransferTaskEntity;
import com.swapops.server.transfer.entity.TransferTaskItemEntity;
import com.swapops.server.transfer.enums.TransferItemStatus;
import com.swapops.server.transfer.enums.TransferStatus;
import com.swapops.server.order.dao.PaymentRecordDao;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.PaymentRecordEntity;
import com.swapops.server.order.entity.SwapOrderEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日终对账（S3.5 + S3.8 增补）：六组不变量核查，输出计数报告（不自动修数——差异升级告警交人工/S3.6）。
 * <ol>
 *   <li>无隔日卡滞活跃单：PENDING_OPEN/OPENED/TAKEN 超过阈值仍活跃；</li>
 *   <li>电池-仓双向一致：battery.cell_id ↔ cell.battery_id；</li>
 *   <li>完成单必有支付流水（SWAP/TAKE 完成窗口内）；</li>
 *   <li>持有人一致：有持有人的电池必为 LOANED 且一人一电；</li>
 *   <li>指令无超龄 PENDING；</li>
 *   <li>无逃逸电池（LOANED 且无仓无持有人，关单后取电产物）。</li>
 * </ol>
 * 核查均为"抽样+计数"口径（样本上限 sampleLimit，报告给差异样例）。
 */
@Slf4j
@Service
public class ReconcileService {

    /** 完成单支付核查涉及的收费类型（套餐扣次金额 0 也算流水存在） */
    private static final List<String> CHARGE_TYPES = List.of(
            "BALANCE_FEE", "DEPOSIT", "OVERDUE_FEE", "PLAN_DEDUCT");

    private final SwapOrderDao orderDao;
    private final BatteryDao batteryDao;
    private final CellDao cellDao;
    private final PaymentRecordDao paymentRecordDao;
    private final CommandLogDao commandLogDao;
    private final BillingProperties billingProperties;
    private final DeviceChannelProperties deviceProperties;
    private final AlarmService alarmService;
    private final BatteryCycleService batteryCycleService;
    private final TransferTaskDao transferTaskDao;
    private final TransferTaskItemDao transferTaskItemDao;
    private final long staleMarginSeconds;
    private final int windowHours;
    private final int sampleLimit;

    public ReconcileService(SwapOrderDao orderDao, BatteryDao batteryDao, CellDao cellDao,
                            PaymentRecordDao paymentRecordDao, CommandLogDao commandLogDao,
                            BillingProperties billingProperties, DeviceChannelProperties deviceProperties,
                            AlarmService alarmService, BatteryCycleService batteryCycleService,
                            TransferTaskDao transferTaskDao, TransferTaskItemDao transferTaskItemDao,
                            @Value("${swap.reconcile.stale-margin-seconds:3600}") long staleMarginSeconds,
                            @Value("${swap.reconcile.window-hours:24}") int windowHours,
                            @Value("${swap.reconcile.sample-limit:200}") int sampleLimit) {
        this.orderDao = orderDao;
        this.batteryDao = batteryDao;
        this.cellDao = cellDao;
        this.paymentRecordDao = paymentRecordDao;
        this.commandLogDao = commandLogDao;
        this.billingProperties = billingProperties;
        this.deviceProperties = deviceProperties;
        this.alarmService = alarmService;
        this.batteryCycleService = batteryCycleService;
        this.transferTaskDao = transferTaskDao;
        this.transferTaskItemDao = transferTaskItemDao;
        this.staleMarginSeconds = staleMarginSeconds;
        this.windowHours = windowHours;
        this.sampleLimit = sampleLimit;
    }

    /** 核查结果（一组不变量） */
    public record CheckResult(String name, int violations, List<String> samples) {
    }

    /** 对账报告 */
    public record ReconcileReport(long runAt, long durationMs, List<CheckResult> checks) {

        public int totalViolations() {
            return checks.stream().mapToInt(CheckResult::violations).sum();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("runAt", runAt);
            map.put("durationMs", durationMs);
            map.put("totalViolations", totalViolations());
            List<Map<String, Object>> checkViews = new ArrayList<>();
            for (CheckResult check : checks) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("name", check.name());
                view.put("violations", check.violations());
                view.put("samples", check.samples());
                checkViews.add(view);
            }
            map.put("checks", checkViews);
            return map;
        }
    }

    public ReconcileReport run() {
        long start = System.currentTimeMillis();
        List<CheckResult> checks = List.of(
                checkStaleActive(),
                checkCellBatteryConsistency(),
                checkCompletedHasPayment(),
                checkHolderConsistency(),
                checkAgingCommands(),
                checkEscapedBatteries(),
                checkBatteryCounters(),
                checkTransferLedger());
        ReconcileReport report = new ReconcileReport(start, System.currentTimeMillis() - start, checks);
        if (report.totalViolations() > 0) {
            alarmService.raise(AlarmService.DEVICE_SYSTEM, "daily-reconcile", AlarmType.RECONCILE_ERROR,
                    "日终对账差异 total=" + report.totalViolations() + " checks=" + summarize(checks));
            log.error("[日终对账] 发现差异 total={} report={}", report.totalViolations(), report.toMap());
        } else {
            alarmService.markRecovered(AlarmService.DEVICE_SYSTEM, "daily-reconcile", AlarmType.RECONCILE_ERROR);
            log.info("[日终对账] 五组不变量零差异 durationMs={}", report.durationMs());
        }
        return report;
    }

    /** ① 无隔日卡滞的活跃单 */
    protected CheckResult checkStaleActive() {
        long now = System.currentTimeMillis();
        List<String> samples = new ArrayList<>();
        int violations = 0;

        long preemptDeadline = now - (billingProperties.getPreemptTtlSeconds() * 1000L
                + staleMarginSeconds * 1000L);
        List<SwapOrderEntity> pending = orderDao.selectList(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getStatus, OrderStatus.PENDING_OPEN.getCode())
                .lt(SwapOrderEntity::getCreateTime, preemptDeadline)
                .last("LIMIT " + sampleLimit));
        violations += pending.size();
        for (SwapOrderEntity order : pending) {
            addSample(samples, "PENDING_OPEN: " + order.getOrderNo() + " createTime=" + order.getCreateTime());
        }

        long pickupDeadline = now - (billingProperties.getPickupTimeoutSeconds() * 1000L
                + staleMarginSeconds * 1000L);
        List<SwapOrderEntity> opened = orderDao.selectList(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getStatus, OrderStatus.OPENED.getCode())
                .lt(SwapOrderEntity::getOpenTime, pickupDeadline)
                .last("LIMIT " + sampleLimit));
        violations += opened.size();
        for (SwapOrderEntity order : opened) {
            addSample(samples, "OPENED: " + order.getOrderNo() + " openTime=" + order.getOpenTime());
        }

        long overdueDeadline = now - (billingProperties.getOverdueHours() * 3600_000L
                + staleMarginSeconds * 1000L);
        List<SwapOrderEntity> taken = orderDao.selectList(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getStatus, OrderStatus.TAKEN.getCode())
                .lt(SwapOrderEntity::getTakeTime, overdueDeadline)
                .last("LIMIT " + sampleLimit));
        violations += taken.size();
        for (SwapOrderEntity order : taken) {
            addSample(samples, "TAKEN: " + order.getOrderNo() + " takeTime=" + order.getTakeTime());
        }
        return new CheckResult("stale-active-orders", violations, samples);
    }

    /** ② 电池-仓双向一致 */
    protected CheckResult checkCellBatteryConsistency() {
        List<String> samples = new ArrayList<>();
        int violations = 0;

        List<CellEntity> cells = cellDao.selectList(new LambdaQueryWrapper<CellEntity>()
                .isNotNull(CellEntity::getBatteryId)
                .last("LIMIT " + sampleLimit));
        for (CellEntity cell : cells) {
            BatteryEntity battery = batteryDao.selectById(cell.getBatteryId());
            if (battery == null || !cell.getId().equals(battery.getCellId())) {
                violations++;
                addSample(samples, "cell→battery: cellId=" + cell.getId() + " batteryId=" + cell.getBatteryId()
                        + " battery.cellId=" + (battery == null ? "missing" : battery.getCellId()));
            }
        }
        List<BatteryEntity> batteries = batteryDao.selectList(new LambdaQueryWrapper<BatteryEntity>()
                .isNotNull(BatteryEntity::getCellId)
                .last("LIMIT " + sampleLimit));
        for (BatteryEntity battery : batteries) {
            CellEntity cell = cellDao.selectById(battery.getCellId());
            if (cell == null || !battery.getId().equals(cell.getBatteryId())) {
                violations++;
                addSample(samples, "battery→cell: batteryNo=" + battery.getBatteryNo()
                        + " cellId=" + battery.getCellId()
                        + " cell.batteryId=" + (cell == null ? "missing" : cell.getBatteryId()));
            }
        }
        return new CheckResult("cell-battery-consistency", violations, samples);
    }

    /** ③ 完成单（SWAP/TAKE）必有支付流水 */
    protected CheckResult checkCompletedHasPayment() {
        long since = System.currentTimeMillis() - windowHours * 3600_000L;
        List<SwapOrderEntity> completed = orderDao.selectList(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getStatus, OrderStatus.COMPLETED.getCode())
                .in(SwapOrderEntity::getOrderType, List.of("SWAP", "TAKE"))
                .ge(SwapOrderEntity::getCompleteTime, since)
                .last("LIMIT " + sampleLimit));
        List<String> samples = new ArrayList<>();
        int violations = 0;
        for (SwapOrderEntity order : completed) {
            Long payments = paymentRecordDao.selectCount(new LambdaQueryWrapper<PaymentRecordEntity>()
                    .eq(PaymentRecordEntity::getOrderId, order.getId())
                    .in(PaymentRecordEntity::getPaymentType, CHARGE_TYPES));
            if (payments == null || payments == 0) {
                violations++;
                addSample(samples, "orderNo=" + order.getOrderNo() + " type=" + order.getOrderType()
                        + " feeFen=" + order.getFeeFen());
            }
        }
        return new CheckResult("completed-has-payment", violations, samples);
    }

    /** ④ 持有人一致：有持有人必为 LOANED；一人一电（DB 唯一键之上的核查） */
    protected CheckResult checkHolderConsistency() {
        List<String> samples = new ArrayList<>();
        int violations = 0;

        List<BatteryEntity> held = batteryDao.selectList(new LambdaQueryWrapper<BatteryEntity>()
                .isNotNull(BatteryEntity::getHolderUserId)
                .last("LIMIT " + sampleLimit));
        for (BatteryEntity battery : held) {
            if (battery.getStatus() == null || battery.getStatus() != BatteryStatus.LOANED.getCode()) {
                violations++;
                addSample(samples, "holder-not-loaned: batteryNo=" + battery.getBatteryNo()
                        + " status=" + battery.getStatus() + " holder=" + battery.getHolderUserId());
            }
        }
        List<Map<String, Object>> duplicates = batteryDao.selectMaps(new QueryWrapper<BatteryEntity>()
                .select("holder_user_id AS holderUserId", "COUNT(*) AS cnt")
                .isNotNull("holder_user_id")
                .groupBy("holder_user_id")
                .having("COUNT(*) > 1"));
        for (Map<String, Object> row : duplicates) {
            violations++;
            addSample(samples, "holder-duplicate: userId=" + row.get("holderUserId") + " count=" + row.get("cnt"));
        }
        return new CheckResult("holder-consistency", violations, samples);
    }

    /**
     * ⑥ 无逃逸电池：BATTERY_OUT 先 detach（LOANED + cell_id=null），持有人由订单域绑定；
     * 若取电时订单已被关单（超时/取消），电池会停在"既不在仓又无人持有"——对账必须显式暴露。
     */
    protected CheckResult checkEscapedBatteries() {
        List<BatteryEntity> escaped = batteryDao.selectList(new LambdaQueryWrapper<BatteryEntity>()
                .eq(BatteryEntity::getStatus, BatteryStatus.LOANED.getCode())
                .isNull(BatteryEntity::getCellId)
                .isNull(BatteryEntity::getHolderUserId)
                .last("LIMIT " + sampleLimit));
        List<String> samples = new ArrayList<>();
        for (BatteryEntity battery : escaped) {
            addSample(samples, "escaped: batteryNo=" + battery.getBatteryNo()
                    + " status=LOANED holder=null cell=null updateTime=" + battery.getUpdateTime());
        }
        return new CheckResult("escaped-batteries", escaped.size(), samples);
    }

    /** ⑧ 调拨台账一致（S4.2）：明细状态与电池实际位置/任务聚合状态三方对照 */
    protected CheckResult checkTransferLedger() {
        List<TransferTaskEntity> tasks = transferTaskDao.selectList(new LambdaQueryWrapper<TransferTaskEntity>()
                .in(TransferTaskEntity::getStatus, TransferStatus.EXECUTING.getCode(),
                        TransferStatus.DONE.getCode())
                .last("LIMIT " + sampleLimit));
        List<String> samples = new ArrayList<>();
        int violations = 0;
        for (TransferTaskEntity task : tasks) {
            List<TransferTaskItemEntity> items = transferTaskItemDao.selectList(
                    new LambdaQueryWrapper<TransferTaskItemEntity>()
                            .eq(TransferTaskItemEntity::getTaskNo, task.getTaskNo()));
            int outCount = 0;
            int inCount = 0;
            for (TransferTaskItemEntity item : items) {
                BatteryEntity battery = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                        .eq(BatteryEntity::getBatteryNo, item.getBatteryNo()));
                if (item.getStatus() == TransferItemStatus.OUT.getCode()) {
                    outCount++;
                    if (battery == null || battery.getCellId() != null || battery.getHolderUserId() != null) {
                        violations++;
                        addSample(samples, "transfer-out-mismatch: " + task.getTaskNo() + "/" + item.getBatteryNo()
                                + " cellId=" + (battery == null ? "missing" : battery.getCellId()));
                    }
                } else if (item.getStatus() == TransferItemStatus.IN.getCode()) {
                    inCount++;
                    if (battery == null || !item.getInCellId().equals(battery.getCellId())) {
                        violations++;
                        addSample(samples, "transfer-in-mismatch: " + task.getTaskNo() + "/" + item.getBatteryNo()
                                + " expectCell=" + item.getInCellId()
                                + " actual=" + (battery == null ? "missing" : battery.getCellId()));
                    }
                }
            }
            boolean aggregateOk = switch (TransferStatus.fromCode(task.getStatus())) {
                case EXECUTING -> outCount >= 1 && inCount < items.size();
                case DONE -> items.size() > 0 && inCount == items.size();
                default -> true;
            };
            if (!aggregateOk) {
                violations++;
                addSample(samples, "transfer-status-mismatch: " + task.getTaskNo()
                        + " status=" + task.getStatus() + " items=" + items.size()
                        + " out=" + outCount + " in=" + inCount);
            }
        }
        return new CheckResult("transfer-ledger", violations, samples);
    }

    /** ⑦ 电池计数与流水一致（S4.1）：计数器是派生值，流水是事实；不一致=计漏/计重或人工改动未留痕 */
    protected CheckResult checkBatteryCounters() {
        List<BatteryEntity> batteries = batteryDao.selectList(new LambdaQueryWrapper<BatteryEntity>()
                .and(w -> w.gt(BatteryEntity::getSwaps, 0).or().gt(BatteryEntity::getCycleCount, 0))
                .last("LIMIT " + sampleLimit));
        List<String> samples = new ArrayList<>();
        int violations = 0;
        for (BatteryEntity battery : batteries) {
            long outLogs = batteryCycleService.countByAction(battery.getBatteryNo(),
                    BatteryCycleService.ACTION_OUT);
            long inLogs = batteryCycleService.countByAction(battery.getBatteryNo(),
                    BatteryCycleService.ACTION_IN);
            int swaps = battery.getSwaps() == null ? 0 : battery.getSwaps();
            int cycles = battery.getCycleCount() == null ? 0 : battery.getCycleCount();
            if (swaps != outLogs || cycles != inLogs) {
                violations++;
                addSample(samples, "counter-mismatch: " + battery.getBatteryNo()
                        + " swaps=" + swaps + "/logs=" + outLogs
                        + " cycles=" + cycles + "/logs=" + inLogs);
            }
        }
        return new CheckResult("battery-counters", violations, samples);
    }

    /** ⑤ 指令无超龄 PENDING（正常应被对账/重试收敛） */
    protected CheckResult checkAgingCommands() {
        long deadline = System.currentTimeMillis()
                - (deviceProperties.getCommandTimeoutSeconds() * 1000L + staleMarginSeconds * 1000L);
        List<CommandLogEntity> aging = commandLogDao.selectList(new LambdaQueryWrapper<CommandLogEntity>()
                .eq(CommandLogEntity::getCommandStatus, 1)
                .lt(CommandLogEntity::getCreateTime, deadline)
                .last("LIMIT " + sampleLimit));
        List<String> samples = new ArrayList<>();
        for (CommandLogEntity cmd : aging) {
            addSample(samples, "PENDING: " + cmd.getCabinetNo() + "-" + cmd.getCommandSeq()
                    + " retry=" + cmd.getRetryCount());
        }
        return new CheckResult("aging-pending-commands", aging.size(), samples);
    }

    private void addSample(List<String> samples, String sample) {
        if (samples.size() < 5) {
            samples.add(sample);
        }
    }

    private String summarize(List<CheckResult> checks) {
        StringBuilder sb = new StringBuilder();
        for (CheckResult check : checks) {
            if (check.violations() > 0) {
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(check.name()).append('=').append(check.violations());
            }
        }
        return sb.toString();
    }
}
