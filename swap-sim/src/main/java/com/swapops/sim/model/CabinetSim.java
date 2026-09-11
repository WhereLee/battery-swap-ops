package com.swapops.sim.model;

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
        if (!cell.isHasBattery()) {
            lastRejectedSeq = seq;
            throw new IllegalStateException("空仓无电池，开仓被拒");
        }
        lastSeq = seq;
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
        log.info("[{}] 取电 cellNo={} batteryNo={}", cabinetNo, cellNo, batteryNo);
        actionExecutor.submit(() -> reportEvent(EventType.BATTERY_OUT, cellNo, batteryNo, null, null, traceId));
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
        log.info("[{}] 还电 cellNo={} batteryNo={} soc={}", cabinetNo, cellNo, batteryNo, soc);
        actionExecutor.submit(() -> reportEvent(EventType.BATTERY_IN, cellNo, batteryNo, soc, null, traceId));
    }

    public void setDoorStuck(boolean stuck) {
        this.doorStuck = stuck;
        log.warn("[{}] 门锁故障注入置为 {}", cabinetNo, stuck);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("cabinetNo", cabinetNo);
        view.put("bootId", bootId);
        view.put("eventSeq", eventCounter.get());
        view.put("lastCommandSeq", lastSeq);
        Map<Integer, Map<String, Object>> cellViews = new LinkedHashMap<>();
        for (Map.Entry<Integer, CellSim> e : cells.entrySet()) {
            CellSim cell = e.getValue();
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("hasBattery", cell.isHasBattery());
            cv.put("batteryNo", cell.getBatteryNo());
            cv.put("soc", cell.getSoc());
            cellViews.put(e.getKey(), cv);
        }
        view.put("cells", cellViews);
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
