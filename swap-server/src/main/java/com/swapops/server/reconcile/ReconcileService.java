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
import com.swapops.server.agent.dao.AgentActionDao;
import com.swapops.server.agent.entity.AgentActionEntity;
import com.swapops.server.agent.enums.AgentActionStatus;
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
 * 日终对账（S3.5 + S3.8 增补；S7 扩至十四组）：十四组不变量核查，输出计数报告（不自动修数——差异升级告警交人工/S3.6）。
 * <ol>
 *   <li>无隔日卡滞活跃单：PENDING_OPEN/OPENED/TAKEN/OVERDUE 超过阈值仍活跃；</li>
 *   <li>电池-仓双向一致：battery.cell_id ↔ cell.battery_id；</li>
 *   <li>完成单必有支付流水（SWAP/TAKE 完成窗口内）；</li>
 *   <li>持有人一致：有持有人的电池必为 LOANED 且一人一电；</li>
 *   <li>指令无超龄 PENDING；</li>
 *   <li>无逃逸电池（LOANED 且无仓无持有人，关单后取电产物）；</li>
 *   <li>电池计数与流水一致（S4.1）；</li>
 *   <li>调拨台账三方一致（S4.2）；</li>
 *   <li>Agent 建议单无悬挂 EXECUTING（S4.6）；</li>
 *   <li>欠费单金额自洽（S7 WP-D）；</li>
 *   <li>券状态守恒（S7 WP-D）；</li>
 *   <li>分账守恒（S7 WP-B）；</li>
 *   <li>结算单与挂单流水一致（S7 WP-B）；</li>
 *   <li>完成单必分账（S7 WP-B）。</li>
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
    private final AgentActionDao agentActionDao;
    private final com.swapops.server.order.dao.ArrearsRecordDao arrearsRecordDao;
    private final com.swapops.server.user.dao.UserCouponDao userCouponDao;
    private final com.swapops.server.settlement.dao.OrderSettlementDao orderSettlementDao;
    private final com.swapops.server.settlement.dao.SettlementStatementDao settlementStatementDao;
    private final long staleMarginSeconds;
    private final int windowHours;
    private final int sampleLimit;

    /** 进行中订单状态（券锁定校验用） */
    private static final List<Integer> ACTIVE_ORDER_STATUSES = List.of(
            OrderStatus.PENDING_OPEN.getCode(), OrderStatus.OPENED.getCode(),
            OrderStatus.TAKEN.getCode(), OrderStatus.OVERDUE.getCode());

    public ReconcileService(SwapOrderDao orderDao, BatteryDao batteryDao, CellDao cellDao,
                            PaymentRecordDao paymentRecordDao, CommandLogDao commandLogDao,
                            BillingProperties billingProperties, DeviceChannelProperties deviceProperties,
                            AlarmService alarmService, BatteryCycleService batteryCycleService,
                            TransferTaskDao transferTaskDao, TransferTaskItemDao transferTaskItemDao,
                            AgentActionDao agentActionDao,
                            com.swapops.server.order.dao.ArrearsRecordDao arrearsRecordDao,
                            com.swapops.server.user.dao.UserCouponDao userCouponDao,
                            com.swapops.server.settlement.dao.OrderSettlementDao orderSettlementDao,
                            com.swapops.server.settlement.dao.SettlementStatementDao settlementStatementDao,
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
        this.agentActionDao = agentActionDao;
        this.arrearsRecordDao = arrearsRecordDao;
        this.userCouponDao = userCouponDao;
        this.orderSettlementDao = orderSettlementDao;
        this.settlementStatementDao = settlementStatementDao;
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
                checkTransferLedger(),
                checkStaleAgentActions(),
                checkArrearsIntegrity(),
                checkCouponConsistency(),
                checkSettlementConservation(),
                checkStatementConsistency(),
                checkSettlementCoverage());
        ReconcileReport report = new ReconcileReport(start, System.currentTimeMillis() - start, checks);
        if (report.totalViolations() > 0) {
            alarmService.raise(AlarmService.DEVICE_SYSTEM, "daily-reconcile", AlarmType.RECONCILE_ERROR,
                    "日终对账差异 total=" + report.totalViolations() + " checks=" + summarize(checks));
            log.error("[日终对账] 发现差异 total={} report={}", report.totalViolations(), report.toMap());
        } else {
            alarmService.markRecovered(AlarmService.DEVICE_SYSTEM, "daily-reconcile", AlarmType.RECONCILE_ERROR);
            log.info("[日终对账] 十四组不变量零差异 durationMs={}", report.durationMs());
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

        // OVERDUE 超长未处置（超过 overdueMaxHours 仍未归还）：扫描任务应已转人工，仍滞留即差异
        long overdueMaxDeadline = now - billingProperties.getOverdueMaxHours() * 3600_000L;
        List<SwapOrderEntity> overdueStales = orderDao.selectList(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getStatus, OrderStatus.OVERDUE.getCode())
                .lt(SwapOrderEntity::getTakeTime, overdueMaxDeadline)
                .last("LIMIT " + sampleLimit));
        violations += overdueStales.size();
        for (SwapOrderEntity order : overdueStales) {
            addSample(samples, "OVERDUE: " + order.getOrderNo() + " takeTime=" + order.getTakeTime());
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

    /** ⑨ Agent 建议单悬挂（S4.6）：EXECUTING 超过 5 分钟=执行后进程崩溃未落终态 → 需人工复核（重新建议） */
    protected CheckResult checkStaleAgentActions() {
        long deadline = System.currentTimeMillis() - 5 * 60_000L;
        List<AgentActionEntity> stale = agentActionDao.selectList(new LambdaQueryWrapper<AgentActionEntity>()
                .eq(AgentActionEntity::getStatus, AgentActionStatus.EXECUTING.getCode())
                .lt(AgentActionEntity::getUpdateTime, deadline)
                .last("LIMIT " + sampleLimit));
        List<String> samples = new ArrayList<>();
        for (AgentActionEntity action : stale) {
            addSample(samples, "stale-agent-action: " + action.getActionNo()
                    + " type=" + action.getActionType());
        }
        return new CheckResult("stale-agent-actions", stale.size(), samples);
    }

    /** ⑫ 分账守恒（S7 WP-B）：每行 agent_share+platform_share=base（不丢分）；ORDER 行基数非负 */
    protected CheckResult checkSettlementConservation() {
        List<com.swapops.server.settlement.entity.OrderSettlementEntity> lines =
                orderSettlementDao.selectList(new LambdaQueryWrapper<com.swapops.server.settlement.entity.OrderSettlementEntity>()
                        .last("LIMIT " + sampleLimit));
        List<String> samples = new ArrayList<>();
        int violations = 0;
        for (com.swapops.server.settlement.entity.OrderSettlementEntity line : lines) {
            int base = line.getBaseAmountFen() == null ? 0 : line.getBaseAmountFen();
            int agent = line.getAgentShareFen() == null ? 0 : line.getAgentShareFen();
            int platform = line.getPlatformShareFen() == null ? 0 : line.getPlatformShareFen();
            boolean orderNonNegative = !"ORDER".equals(line.getEventType()) || base >= 0;
            if (agent + platform != base || !orderNonNegative) {
                violations++;
                addSample(samples, "settlement-mismatch: key=" + line.getEventKey()
                        + " base=" + base + " agent=" + agent + " platform=" + platform);
            }
        }
        return new CheckResult("settlement-conservation", violations, samples);
    }

    /** ⑬ 结算单一致（S7 WP-B）：挂单流水合计=结算单金额；无孤儿挂单（指向不存在结算单） */
    protected CheckResult checkStatementConsistency() {
        List<String> samples = new ArrayList<>();
        int violations = 0;
        List<com.swapops.server.settlement.entity.SettlementStatementEntity> statements =
                settlementStatementDao.selectList(new LambdaQueryWrapper<com.swapops.server.settlement.entity.SettlementStatementEntity>()
                        .last("LIMIT " + sampleLimit));
        Map<Long, com.swapops.server.settlement.entity.SettlementStatementEntity> byId = new LinkedHashMap<>();
        for (var statement : statements) {
            byId.put(statement.getId(), statement);
        }
        List<com.swapops.server.settlement.entity.OrderSettlementEntity> linked =
                orderSettlementDao.selectList(new LambdaQueryWrapper<com.swapops.server.settlement.entity.OrderSettlementEntity>()
                        .isNotNull(com.swapops.server.settlement.entity.OrderSettlementEntity::getStatementId)
                        .last("LIMIT " + sampleLimit));
        Map<Long, int[]> sums = new LinkedHashMap<>();
        for (var line : linked) {
            Long sid = line.getStatementId();
            if (!byId.containsKey(sid)) {
                violations++;
                addSample(samples, "orphan-settlement-line: key=" + line.getEventKey() + " statementId=" + sid);
                continue;
            }
            int[] acc = sums.computeIfAbsent(sid, k -> new int[4]);
            acc[0] += line.getBaseAmountFen() == null ? 0 : line.getBaseAmountFen();
            acc[1] += line.getAgentShareFen() == null ? 0 : line.getAgentShareFen();
            acc[2] += line.getPlatformShareFen() == null ? 0 : line.getPlatformShareFen();
            acc[3] += line.getSubsidyFen() == null ? 0 : line.getSubsidyFen();
        }
        for (var statement : statements) {
            int[] acc = sums.get(statement.getId());
            if (acc == null) {
                continue; // 空单（生成后流水被并发领取的残留）由生成事务保证不出现
            }
            if (statement.getBaseAmountFen() == null || statement.getBaseAmountFen() != acc[0]
                    || statement.getAgentAmountFen() == null || statement.getAgentAmountFen() != acc[1]
                    || statement.getPlatformAmountFen() == null || statement.getPlatformAmountFen() != acc[2]
                    || statement.getSubsidyFen() == null || statement.getSubsidyFen() != acc[3]) {
                violations++;
                addSample(samples, "statement-mismatch: " + statement.getStatementNo()
                        + " stmt=" + statement.getBaseAmountFen() + "/" + statement.getAgentAmountFen()
                        + " lines=" + acc[0] + "/" + acc[1]);
            }
        }
        return new CheckResult("statement-consistency", violations, samples);
    }

    /** ⑭ 完成单必分账（S7 WP-B）：窗口内已完成 TAKE/SWAP（已计费）必有 ORDER 分账流水（对冲正/补缴不重复要求） */
    protected CheckResult checkSettlementCoverage() {
        long since = System.currentTimeMillis() - windowHours * 3600_000L;
        List<SwapOrderEntity> completed = orderDao.selectList(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getStatus, OrderStatus.COMPLETED.getCode())
                .in(SwapOrderEntity::getOrderType, List.of("SWAP", "TAKE"))
                .isNotNull(SwapOrderEntity::getPayType)
                .ge(SwapOrderEntity::getCompleteTime, since)
                .last("LIMIT " + sampleLimit));
        List<String> samples = new ArrayList<>();
        int violations = 0;
        for (SwapOrderEntity order : completed) {
            Long count = orderSettlementDao.selectCount(
                    new LambdaQueryWrapper<com.swapops.server.settlement.entity.OrderSettlementEntity>()
                            .eq(com.swapops.server.settlement.entity.OrderSettlementEntity::getEventKey,
                                    order.getOrderNo() + ":ORDER"));
            if (count == null || count == 0) {
                violations++;
                addSample(samples, "completed-unsettled: " + order.getOrderNo() + " payType=" + order.getPayType());
            }
        }
        return new CheckResult("settlement-coverage", violations, samples);
    }

    /** ⑩ 欠费单一致（S7 WP-D）：OPEN 欠费金额自洽（amount>settled>=0），异常值暴露交人工 */
    protected CheckResult checkArrearsIntegrity() {
        List<com.swapops.server.order.entity.ArrearsRecordEntity> open =
                arrearsRecordDao.selectList(new LambdaQueryWrapper<com.swapops.server.order.entity.ArrearsRecordEntity>()
                        .eq(com.swapops.server.order.entity.ArrearsRecordEntity::getStatus, 1)
                        .last("LIMIT " + sampleLimit));
        List<String> samples = new ArrayList<>();
        int violations = 0;
        for (com.swapops.server.order.entity.ArrearsRecordEntity record : open) {
            int amount = record.getAmountFen() == null ? 0 : record.getAmountFen();
            int settled = record.getSettledFen() == null ? 0 : record.getSettledFen();
            if (amount <= 0 || settled < 0 || settled >= amount) {
                violations++;
                addSample(samples, "arrears-inconsistent: id=" + record.getId()
                        + " orderNo=" + record.getOrderNo() + " amount=" + amount + " settled=" + settled);
            }
        }
        return new CheckResult("arrears-integrity", violations, samples);
    }

    /** ⑪ 券状态守恒（S7 WP-D）：LOCKED 必挂进行中订单；USED 必挂完成订单（悬挂显性化） */
    protected CheckResult checkCouponConsistency() {
        List<String> samples = new ArrayList<>();
        int violations = 0;
        List<com.swapops.server.user.entity.UserCouponEntity> locked =
                userCouponDao.selectList(new LambdaQueryWrapper<com.swapops.server.user.entity.UserCouponEntity>()
                        .eq(com.swapops.server.user.entity.UserCouponEntity::getStatus,
                                com.swapops.server.user.enums.UserCouponStatus.LOCKED.getCode())
                        .last("LIMIT " + sampleLimit));
        for (com.swapops.server.user.entity.UserCouponEntity coupon : locked) {
            SwapOrderEntity order = coupon.getLockedOrderId() == null ? null
                    : orderDao.selectById(coupon.getLockedOrderId());
            if (order == null || order.getStatus() == null
                    || !ACTIVE_ORDER_STATUSES.contains(order.getStatus())) {
                violations++;
                addSample(samples, "coupon-locked-stale: couponId=" + coupon.getId()
                        + " lockedOrderId=" + coupon.getLockedOrderId()
                        + " orderStatus=" + (order == null ? "missing" : order.getStatus()));
            }
        }
        List<com.swapops.server.user.entity.UserCouponEntity> used =
                userCouponDao.selectList(new LambdaQueryWrapper<com.swapops.server.user.entity.UserCouponEntity>()
                        .eq(com.swapops.server.user.entity.UserCouponEntity::getStatus,
                                com.swapops.server.user.enums.UserCouponStatus.USED.getCode())
                        .last("LIMIT " + sampleLimit));
        for (com.swapops.server.user.entity.UserCouponEntity coupon : used) {
            SwapOrderEntity order = coupon.getUsedOrderId() == null ? null
                    : orderDao.selectById(coupon.getUsedOrderId());
            if (order == null || order.getStatus() == null
                    || order.getStatus() != OrderStatus.COMPLETED.getCode()) {
                violations++;
                addSample(samples, "coupon-used-mismatch: couponId=" + coupon.getId()
                        + " usedOrderId=" + coupon.getUsedOrderId());
            }
        }
        return new CheckResult("coupon-consistency", violations, samples);
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
