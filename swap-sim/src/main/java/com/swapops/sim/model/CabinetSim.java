package com.swapops.sim.model;

import com.swapops.contract.CabinetStatus;
import com.swapops.contract.EventType;
import com.swapops.sim.config.SimProperties;
import com.swapops.sim.reporter.DeviceEventMessage;
import com.swapops.sim.reporter.EventReporter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟柜：裁决（synchronized）与动作/上报（异步、锁外 IO）分离；
 * seq 幂等双线（已受理 lastSeq / 已被拒 lastRejectedSeq）——与平台契约一致。
 */
@Slf4j
public class CabinetSim {

    @Getter
    private final String cabinetNo;

    @Getter
    private final String bootId;

    private final SimProperties properties;

    private final EventReporter reporter;

    /** 柜内电池编号偏移（与平台种子一致：(柜序-1) × 每柜仓数） */
    private final int batteryOffset;

    private final Map<Integer, CellSim> cells = new ConcurrentHashMap<>();

    private final ExecutorService actionExecutor;

    private final AtomicLong eventCounter = new AtomicLong();

    private volatile long lastSeq = 0L;

    private volatile long lastRejectedSeq = -1L;

    /** 故障注入：门锁卡死（拒绝开仓） */
    private volatile boolean doorStuck = false;

    /** 充电策略（S4.3）：null=未下发，用 sim 默认功率 */
    private volatile ChargePolicy chargePolicy;

    /** 最近一次充电 tick 的实际功率（W，供功率不超限断言） */
    private volatile int chargingPowerW;

    /** 最近一次充电 tick 的实际功率（供查询/剧本断言） */
    public int getChargingPowerW() {
        return chargingPowerW;
    }

    public ChargePolicy getChargePolicy() {
        return chargePolicy;
    }

    /**
     * 下发策略（版本单调）：新版本应用；同版本幂等忽略；旧版本拒绝。
     *
     * @return true=已应用；false=同版本重复（幂等忽略）
     */
    public synchronized boolean applyPolicy(ChargePolicy policy) {
        ChargePolicy current = this.chargePolicy;
        if (current != null) {
            if (policy.version() < current.version()) {
                throw new IllegalStateException("策略版本回退被拒: " + policy.version() + " < " + current.version());
            }
            if (policy.version() == current.version()) {
                log.info("[{}] 同版本策略幂等忽略 version={}", cabinetNo, policy.version());
                return false;
            }
        }
        this.chargePolicy = policy;
        log.info("[{}] 充电策略已应用 version={} priority={} windows={}",
                cabinetNo, policy.version(), policy.priority(), policy.windows().size());
        return true;
    }

    /**
     * 充电仿真 tick（S4.3）：按当前小时窗口的功率预算给低电量电池充电；
     * 每颗按 per=max(0, min(单颗上限, 预算/在充数)) 功率推进 SOC，预算不足=排队（本次不充）。
     * 时间是注入参数（调度器传真实增量；单测可传固定值）。
     */
    public void chargeTick(long nowMillis, long deltaMillis) {
        // 用注入时钟取小时（与 delta 同源，测试可注入固定时刻）
        int hour = java.time.Instant.ofEpochMilli(nowMillis)
                .atZone(java.time.ZoneId.systemDefault()).getHour();
        int budgetW = chargePolicy == null
                ? properties.getDefaultChargePowerW() : chargePolicy.powerLimitAt(hour);
        java.util.List<CellSim> charging = new java.util.ArrayList<>();
        synchronized (this) {
            for (CellSim cell : cells.values()) {
                if (cell.isHasBattery() && cell.getSoc() < 100) {
                    charging.add(cell);
                }
            }
            int maxPerBattery = properties.getMaxChargePowerW();
            int count = budgetW <= 0 ? 0 : Math.min(charging.size(), Math.max(1, budgetW / maxPerBattery));
            int perW = count == 0 ? 0 : Math.min(maxPerBattery, budgetW / count);
            for (int i = 0; i < count; i++) {
                CellSim cell = charging.get(i);
                double energyWh = perW * properties.getChargeEfficiency()
                        * (deltaMillis / 3600_000.0) * properties.getChargeSpeedFactor();
                double socGain = energyWh / properties.getBatteryCapacityWh() * 100.0;
                int soc = Math.min(100, (int) Math.round(cell.getSoc() + socGain));
                cell.setSoc(soc);
            }
            chargingPowerW = perW * count;
        }
    }

    /** 开门会话（cellNo → commandSeq/时间）：同一会话内的取/还电事件回带该 seq（协议会话关联） */
    private final Map<Integer, Long> sessionSeq = new ConcurrentHashMap<>();

    private final Map<Integer, Long> sessionAt = new ConcurrentHashMap<>();

    /** 会话保鲜窗（毫秒）：超窗的开门会话不再回带 seq（防陈旧关联） */
    private static final long SESSION_FRESH_MILLIS = 5 * 60 * 1000L;

    public CabinetSim(String cabinetNo, String bootId, SimProperties properties,
                      EventReporter reporter, int batteryOffset) {
        this.cabinetNo = cabinetNo;
        this.bootId = bootId;
        this.properties = properties;
        this.reporter = reporter;
        this.batteryOffset = batteryOffset;
        for (int i = 1; i <= properties.getCellsPerCabinet(); i++) {
            boolean full = i <= properties.getFullCells();
            String batteryNo = full ? String.format("BAT-%04d", batteryOffset + i) : null;
            cells.put(i, new CellSim(i, full, batteryNo, full ? 100 : 0));
        }
        this.actionExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cabinet-" + cabinetNo);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 开仓（门动作异步执行并上报 DOOR_OPENED）。
     *
     * @return true=受理；false=重复/乱序指令幂等忽略
     * @throws IllegalStateException 门锁故障/空仓无电池/曾被拒 seq 重试
     */
    public synchronized boolean openCell(int cellNo, long seq, String traceId) {
        if (seq == lastRejectedSeq) {
            throw new IllegalStateException("该指令 seq=" + seq + " 曾被拒绝，重试无意义");
        }
        if (seq <= lastSeq) {
            log.info("[{}] 重复/乱序指令幂等忽略 seq={} (lastSeq={})", cabinetNo, seq, lastSeq);
            return false;
        }
        CellSim cell = cells.get(cellNo);
        if (cell == null) {
            lastRejectedSeq = seq;
            throw new IllegalStateException("仓不存在: " + cellNo);
        }
        if (doorStuck) {
            lastRejectedSeq = seq;
            throw new IllegalStateException("门锁故障，开仓被拒");
        }
        // 空仓也可开门：RETURN（退租）订单就是"开空仓→放入电池"；物理门不区分取/还
        lastSeq = seq;
        sessionSeq.put(cellNo, seq);
        sessionAt.put(cellNo, System.currentTimeMillis());
        long delay = properties.getOpenDelayMillis();
        actionExecutor.submit(() -> {
            sleep(delay);
            reportEvent(EventType.DOOR_OPENED, cellNo, null, null, seq, traceId);
        });
        return true;
    }

    /** 联调：模拟取电（BATTERY_OUT） */
    public void devTake(int cellNo, String traceId) {
        String batteryNo;
        synchronized (this) {
            CellSim cell = cells.get(cellNo);
            if (cell == null) {
                throw new IllegalArgumentException("仓不存在: " + cellNo);
            }
            if (!cell.isHasBattery()) {
                throw new IllegalStateException("仓内无电池: " + cellNo);
            }
            batteryNo = cell.takeBattery();
        }
        Long commandSeq = freshSessionSeq(cellNo);
        log.info("[{}] 取电 cellNo={} batteryNo={} sessionSeq={}", cabinetNo, cellNo, batteryNo, commandSeq);
        actionExecutor.submit(() -> reportEvent(EventType.BATTERY_OUT, cellNo, batteryNo, null, commandSeq, traceId));
    }

    /** 联调：模拟还电（BATTERY_IN） */
    public void devPut(int cellNo, String batteryNo, int soc, String traceId) {
        synchronized (this) {
            CellSim cell = cells.get(cellNo);
            if (cell == null) {
                throw new IllegalArgumentException("仓不存在: " + cellNo);
            }
            cell.putBattery(batteryNo, soc);
        }
        Long commandSeq = freshSessionSeq(cellNo);
        // 会话在还电后终结（一次开门会话最多一取一还）
        sessionSeq.remove(cellNo);
        sessionAt.remove(cellNo);
        log.info("[{}] 还电 cellNo={} batteryNo={} soc={} sessionSeq={}", cabinetNo, cellNo, batteryNo, soc, commandSeq);
        actionExecutor.submit(() -> reportEvent(EventType.BATTERY_IN, cellNo, batteryNo, soc, commandSeq, traceId));
    }

    /** 取当前开门会话的 seq（超窗/不存在返回 null） */
    private Long freshSessionSeq(int cellNo) {
        Long at = sessionAt.get(cellNo);
        if (at == null || System.currentTimeMillis() - at > SESSION_FRESH_MILLIS) {
            sessionSeq.remove(cellNo);
            sessionAt.remove(cellNo);
            return null;
        }
        return sessionSeq.get(cellNo);
    }

    public void setDoorStuck(boolean stuck) {
        this.doorStuck = stuck;
        log.warn("[{}] 门锁故障注入置为 {}", cabinetNo, stuck);
    }

    /** 心跳自述状态：无空仓上报 FULL(2)，否则 ONLINE(1)（S0.3 §2.1 语义） */
    public int reportStatus() {
        for (CellSim cell : cells.values()) {
            if (!cell.isHasBattery()) {
                return CabinetStatus.ONLINE.getCode();
            }
        }
        return CabinetStatus.FULL.getCode();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("cabinetNo", cabinetNo);
        view.put("bootId", bootId);
        view.put("eventSeq", eventCounter.get());
        view.put("lastCommandSeq", lastSeq);
        Map<Integer, Map<String, Object>> cellViews = new LinkedHashMap<>();
        synchronized (this) {
            for (Map.Entry<Integer, CellSim> e : cells.entrySet()) {
                CellSim cell = e.getValue();
                Map<String, Object> cv = new LinkedHashMap<>();
                cv.put("hasBattery", cell.isHasBattery());
                cv.put("batteryNo", cell.getBatteryNo());
                cv.put("soc", cell.getSoc());
                cellViews.put(e.getKey(), cv);
            }
        }
        view.put("cells", cellViews);
        view.put("chargingPowerW", chargingPowerW);
        ChargePolicy policy = chargePolicy;
        view.put("policyVersion", policy == null ? null : policy.version());
        return view;
    }

    public void shutdown() {
        actionExecutor.shutdownNow();
    }

    private void reportEvent(EventType type, Integer cellNo, String batteryNo, Integer soc,
                             Long commandSeq, String traceId) {
        long eventSeq = eventCounter.incrementAndGet();
        reporter.report(new DeviceEventMessage(cabinetNo, type, cellNo, batteryNo, soc,
                commandSeq, bootId, eventSeq, traceId));
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
