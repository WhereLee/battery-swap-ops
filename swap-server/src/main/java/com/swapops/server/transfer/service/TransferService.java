package com.swapops.server.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.swapops.contract.BatteryStatus;
import com.swapops.contract.CellStatus;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.asset.entity.StationEntity;
import com.swapops.server.common.RRException;
import com.swapops.server.common.id.SnowflakeIdGenerator;
import com.swapops.server.common.utils.PageParams;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.order.service.AllocationService;
import com.swapops.server.transfer.config.TransferProperties;
import com.swapops.server.transfer.dao.TransferTaskDao;
import com.swapops.server.transfer.dao.TransferTaskItemDao;
import com.swapops.server.transfer.entity.TransferTaskEntity;
import com.swapops.server.transfer.entity.TransferTaskItemEntity;
import com.swapops.server.transfer.enums.TransferItemStatus;
import com.swapops.server.transfer.enums.TransferStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站间调拨（S4.2，启发式）：供需建议（就近配对）→ 任务状态机 → 逐电池出/入库台账跟踪。
 * 执行=人工确认（管理员点出库/入库）；守卫严格（站属/仓空/状态 CAS），EXECUTING 不可取消。
 */
@Slf4j
@Service
public class TransferService {

    private final TransferTaskDao taskDao;
    private final TransferTaskItemDao itemDao;
    private final StationDao stationDao;
    private final CabinetDao cabinetDao;
    private final CellDao cellDao;
    private final BatteryDao batteryDao;
    private final AllocationService allocationService;
    private final SnowflakeIdGenerator idGenerator;
    private final TransferProperties properties;
    private final DeviceChannelProperties deviceProperties;

    public TransferService(TransferTaskDao taskDao, TransferTaskItemDao itemDao, StationDao stationDao,
                           CabinetDao cabinetDao, CellDao cellDao, BatteryDao batteryDao,
                           AllocationService allocationService, SnowflakeIdGenerator idGenerator,
                           TransferProperties properties, DeviceChannelProperties deviceProperties) {
        this.taskDao = taskDao;
        this.itemDao = itemDao;
        this.stationDao = stationDao;
        this.cabinetDao = cabinetDao;
        this.cellDao = cellDao;
        this.batteryDao = batteryDao;
        this.allocationService = allocationService;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.deviceProperties = deviceProperties;
    }

    // ---------- 供需建议（只读，不落库） ----------

    public List<Map<String, Object>> recommend() {
        List<StationEntity> stations = stationDao.selectList(new LambdaQueryWrapper<StationEntity>()
                .eq(StationEntity::getStatus, 1).orderByAsc(StationEntity::getId));
        List<StationAvail> avails = new ArrayList<>();
        for (StationEntity station : stations) {
            long full = 0;
            List<CabinetEntity> cabinets = cabinetDao.selectList(new LambdaQueryWrapper<CabinetEntity>()
                    .eq(CabinetEntity::getStationId, station.getId()));
            for (CabinetEntity cabinet : cabinets) {
                full += allocationService.countAvailable(cabinet.getCabinetNo(), true);
            }
            avails.add(new StationAvail(station, full));
        }
        List<StationAvail> deficits = avails.stream()
                .filter(a -> a.full < properties.getDeficitTargetLevel())
                .sorted((a, b) -> Long.compare(a.full, b.full))
                .toList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (StationAvail deficit : deficits) {
            StationAvail best = null;
            double bestDistance = Double.MAX_VALUE;
            boolean distanceUnknown = false;
            for (StationAvail candidate : avails) {
                if (candidate.station.getId().equals(deficit.station.getId())) {
                    continue;
                }
                long surplus = candidate.full - properties.getSurplusKeepLevel();
                if (surplus <= 0) {
                    continue;
                }
                Double distance = haversineKm(candidate.station, deficit.station);
                double effective = distance == null ? Double.MAX_VALUE : distance;
                if (best == null || effective < bestDistance) {
                    best = candidate;
                    bestDistance = effective;
                    distanceUnknown = distance == null;
                }
            }
            if (best == null) {
                continue;
            }
            long quantity = Math.min(Math.min(
                    properties.getDeficitTargetLevel() - deficit.full,
                    best.full - properties.getSurplusKeepLevel()),
                    properties.getMaxPerTask());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("fromStationId", best.station.getId());
            view.put("fromStationNo", best.station.getStationNo());
            view.put("fromFullCount", best.full);
            view.put("toStationId", deficit.station.getId());
            view.put("toStationNo", deficit.station.getStationNo());
            view.put("toFullCount", deficit.full);
            view.put("quantity", quantity);
            view.put("distanceKm", distanceUnknown ? null : Math.round(bestDistance * 10) / 10.0);
            view.put("distanceUnknown", distanceUnknown);
            result.add(view);
        }
        return result;
    }

    private Double haversineKm(StationEntity a, StationEntity b) {
        if (a.getLatitude() == null || a.getLongitude() == null
                || b.getLatitude() == null || b.getLongitude() == null) {
            return null; // 坐标缺失不伪造距离，如实标注
        }
        double earthRadius = 6371.0;
        double dLat = Math.toRadians(b.getLatitude() - a.getLatitude());
        double dLng = Math.toRadians(b.getLongitude() - a.getLongitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.getLatitude())) * Math.cos(Math.toRadians(b.getLatitude()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * earthRadius * Math.asin(Math.sqrt(h));
    }

    private record StationAvail(StationEntity station, long full) {
    }

    // ---------- 任务生命周期 ----------

    @Transactional
    public Map<String, Object> create(Long fromStationId, Long toStationId, Integer count, String operator) {
        if (fromStationId == null || toStationId == null || fromStationId.equals(toStationId)) {
            throw new RRException("调出/调入站点必填且不能相同");
        }
        if (count == null || count < 1 || count > properties.getMaxPerTask()) {
            throw new RRException("调拨数量需在 1~" + properties.getMaxPerTask() + " 之间");
        }
        StationEntity from = requireActiveStation(fromStationId);
        requireActiveStation(toStationId);

        List<BatteryEntity> candidates = fullBatteriesOfStation(from.getId(), count);
        if (candidates.size() < count) {
            throw new RRException("调出站可用满电电池不足（可用 " + candidates.size() + "，需 " + count + "）");
        }
        long now = System.currentTimeMillis();
        TransferTaskEntity task = new TransferTaskEntity();
        task.setTaskNo("TR" + idGenerator.nextIdString());
        task.setFromStation(from.getId());
        task.setToStation(toStationId);
        task.setPlanCount(count);
        task.setStatus(TransferStatus.DRAFT.getCode());
        task.setCreatedBy(operator);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        taskDao.insert(task);
        for (BatteryEntity battery : candidates) {
            TransferTaskItemEntity item = new TransferTaskItemEntity();
            item.setTaskNo(task.getTaskNo());
            item.setBatteryNo(battery.getBatteryNo());
            item.setStatus(TransferItemStatus.PENDING.getCode());
            item.setCreateTime(now);
            item.setUpdateTime(now);
            itemDao.insert(item);
        }
        log.info("[调拨] 创建 taskNo={} {} -> {} count={}", task.getTaskNo(),
                from.getStationNo(), toStationId, count);
        return detail(task.getId());
    }

    /** 源站指定仓内满电电池（按 id 稳定序，保证脚本/审核可复现） */
    private List<BatteryEntity> fullBatteriesOfStation(Long stationId, int limit) {
        List<CabinetEntity> cabinets = cabinetDao.selectList(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getStationId, stationId));
        if (cabinets.isEmpty()) {
            return List.of();
        }
        List<Long> cabinetIds = cabinets.stream().map(CabinetEntity::getId).toList();
        List<CellEntity> cells = cellDao.selectList(new LambdaQueryWrapper<CellEntity>()
                .in(CellEntity::getCabinetId, cabinetIds)
                .isNotNull(CellEntity::getBatteryId));
        List<Long> batteryIds = cells.stream().map(CellEntity::getBatteryId).toList();
        if (batteryIds.isEmpty()) {
            return List.of();
        }
        return batteryDao.selectList(new LambdaQueryWrapper<BatteryEntity>()
                .in(BatteryEntity::getId, batteryIds)
                .eq(BatteryEntity::getStatus, BatteryStatus.FULL.getCode())
                .orderByAsc(BatteryEntity::getId)
                .last("LIMIT " + limit));
    }

    public TransferTaskEntity approve(Long id, String operator) {
        TransferTaskEntity task = require(id);
        boolean moved = casTask(id, TransferStatus.DRAFT, TransferStatus.APPROVED,
                w -> w.set(TransferTaskEntity::getApprovedBy, operator));
        if (!moved) {
            throw new RRException("任务当前状态不可审批: " + TransferStatus.fromCode(task.getStatus()));
        }
        return require(id);
    }

    public TransferTaskEntity cancel(Long id, String operator) {
        TransferTaskEntity task = require(id);
        boolean moved = casTask(id, TransferStatus.DRAFT, TransferStatus.CANCELLED, w -> { })
                || casTask(id, TransferStatus.APPROVED, TransferStatus.CANCELLED, w -> { });
        if (!moved) {
            throw new RRException("任务当前状态不可取消（EXECUTING 必须闭环）: "
                    + TransferStatus.fromCode(task.getStatus()));
        }
        log.warn("[调拨] 取消 taskNo={} by={}", task.getTaskNo(), operator);
        return require(id);
    }

    @Transactional
    public Map<String, Object> out(Long id, String batteryNo, String operator) {
        TransferTaskEntity task = require(id);
        TransferStatus status = TransferStatus.fromCode(task.getStatus());
        if (status != TransferStatus.APPROVED && status != TransferStatus.EXECUTING) {
            throw new RRException("任务未审批或已结束，不能出库: " + status);
        }
        TransferTaskItemEntity item = requireItem(task.getTaskNo(), batteryNo);
        boolean itemMoved = casItem(item.getId(), TransferItemStatus.PENDING, TransferItemStatus.OUT, w -> { });
        if (!itemMoved) {
            throw new RRException("明细状态不允许出库: "
                    + TransferItemStatus.fromCode(item.getStatus()));
        }
        BatteryEntity battery = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                .eq(BatteryEntity::getBatteryNo, batteryNo));
        if (battery == null || battery.getHolderUserId() != null || battery.getCellId() == null) {
            throw new RRException("电池不满足出库条件（须在仓且无持有人）: " + batteryNo);
        }
        CellEntity cell = cellDao.selectById(battery.getCellId());
        if (cell == null || !cellBelongsToStation(cell.getCabinetId(), task.getFromStation())) {
            throw new RRException("电池不在调出站仓内: " + batteryNo);
        }
        if (cell.getLockOrderId() != null) {
            throw new RRException("电池所在仓已被订单锁定（有进行中的换电单），不能出库: " + batteryNo);
        }
        long now = System.currentTimeMillis();
        cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                .eq(CellEntity::getId, cell.getId())
                .set(CellEntity::getBatteryId, null)
                .set(CellEntity::getStatus, CellStatus.EMPTY.getCode())
                .set(CellEntity::getUpdateTime, now));
        batteryDao.update(null, new LambdaUpdateWrapper<BatteryEntity>()
                .eq(BatteryEntity::getId, battery.getId())
                .set(BatteryEntity::getCellId, null)
                .set(BatteryEntity::getHolderUserId, null)
                .set(BatteryEntity::getStatus, BatteryStatus.CHARGING.getCode())
                .set(BatteryEntity::getUpdateTime, now));
        itemDao.update(null, new LambdaUpdateWrapper<TransferTaskItemEntity>()
                .eq(TransferTaskItemEntity::getId, item.getId())
                .set(TransferTaskItemEntity::getOutCellId, cell.getId())
                .set(TransferTaskItemEntity::getOutTime, now)
                .set(TransferTaskItemEntity::getUpdateTime, now));
        if (status == TransferStatus.APPROVED) {
            casTask(id, TransferStatus.APPROVED, TransferStatus.EXECUTING, w -> { });
        }
        allocationService.rebuildFromDb();
        log.info("[调拨] 出库 taskNo={} batteryNo={} fromCell={}", task.getTaskNo(), batteryNo, cell.getId());
        return detail(id);
    }

    @Transactional
    public Map<String, Object> in(Long id, String batteryNo, Long cellId, String operator) {
        TransferTaskEntity task = require(id);
        if (TransferStatus.fromCode(task.getStatus()) != TransferStatus.EXECUTING) {
            throw new RRException("任务未处于执行中，不能入库");
        }
        TransferTaskItemEntity item = requireItem(task.getTaskNo(), batteryNo);
        boolean itemMoved = casItem(item.getId(), TransferItemStatus.OUT, TransferItemStatus.IN, w -> { });
        if (!itemMoved) {
            throw new RRException("明细状态不允许入库: " + TransferItemStatus.fromCode(item.getStatus()));
        }
        BatteryEntity battery = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                .eq(BatteryEntity::getBatteryNo, batteryNo));
        if (battery == null || battery.getCellId() != null || battery.getHolderUserId() != null) {
            throw new RRException("电池不满足入库条件（须在途）: " + batteryNo);
        }
        CellEntity target = cellDao.selectById(cellId);
        if (target == null || target.getBatteryId() != null || target.getLockOrderId() != null) {
            throw new RRException("目标仓不存在或非空/锁定: " + cellId);
        }
        if (!cellBelongsToStation(target.getCabinetId(), task.getToStation())) {
            throw new RRException("目标仓不属于调入站: " + cellId);
        }
        long now = System.currentTimeMillis();
        cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                .eq(CellEntity::getId, target.getId())
                .set(CellEntity::getBatteryId, battery.getId())
                .set(CellEntity::getStatus, CellStatus.OCCUPIED.getCode())
                .set(CellEntity::getUpdateTime, now));
        int soc = battery.getSoc() == null ? 0 : battery.getSoc();
        batteryDao.update(null, new LambdaUpdateWrapper<BatteryEntity>()
                .eq(BatteryEntity::getId, battery.getId())
                .set(BatteryEntity::getCellId, target.getId())
                .set(BatteryEntity::getStatus, soc >= deviceProperties.getSocFullThreshold()
                        ? BatteryStatus.FULL.getCode() : BatteryStatus.CHARGING.getCode())
                .set(BatteryEntity::getUpdateTime, now));
        itemDao.update(null, new LambdaUpdateWrapper<TransferTaskItemEntity>()
                .eq(TransferTaskItemEntity::getId, item.getId())
                .set(TransferTaskItemEntity::getInCellId, target.getId())
                .set(TransferTaskItemEntity::getInTime, now)
                .set(TransferTaskItemEntity::getUpdateTime, now));
        // 明细全 IN → 任务 DONE（聚合推进，不靠人工点完成）
        Long pending = itemDao.selectCount(new LambdaQueryWrapper<TransferTaskItemEntity>()
                .eq(TransferTaskItemEntity::getTaskNo, task.getTaskNo())
                .ne(TransferTaskItemEntity::getStatus, TransferItemStatus.IN.getCode()));
        if (pending != null && pending == 0) {
            casTask(id, TransferStatus.EXECUTING, TransferStatus.DONE, w -> { });
        }
        allocationService.rebuildFromDb();
        log.info("[调拨] 入库 taskNo={} batteryNo={} toCell={}", task.getTaskNo(), batteryNo, target.getId());
        return detail(id);
    }

    public PageResult<TransferTaskEntity> page(Integer page, Integer limit, Integer status) {
        IPage<TransferTaskEntity> result = taskDao.selectPage(
                new Page<>(PageParams.page(page), PageParams.limit(limit)),
                new LambdaQueryWrapper<TransferTaskEntity>()
                        .eq(status != null, TransferTaskEntity::getStatus, status)
                        .orderByDesc(TransferTaskEntity::getCreateTime));
        return PageResult.of(result);
    }

    public Map<String, Object> detail(Long id) {
        TransferTaskEntity task = require(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("task", task);
        view.put("items", itemDao.selectList(new LambdaQueryWrapper<TransferTaskItemEntity>()
                .eq(TransferTaskItemEntity::getTaskNo, task.getTaskNo())
                .orderByAsc(TransferTaskItemEntity::getId)));
        return view;
    }

    // ---------- 内部 ----------

    private TransferTaskEntity require(Long id) {
        TransferTaskEntity task = taskDao.selectById(id);
        if (task == null) {
            throw new RRException("调拨任务不存在: " + id);
        }
        return task;
    }

    private TransferTaskItemEntity requireItem(String taskNo, String batteryNo) {
        TransferTaskItemEntity item = itemDao.selectOne(new LambdaQueryWrapper<TransferTaskItemEntity>()
                .eq(TransferTaskItemEntity::getTaskNo, taskNo)
                .eq(TransferTaskItemEntity::getBatteryNo, batteryNo));
        if (item == null) {
            throw new RRException("任务中无该电池明细: " + batteryNo);
        }
        return item;
    }

    private StationEntity requireActiveStation(Long id) {
        StationEntity station = stationDao.selectById(id);
        if (station == null || station.getStatus() == null || station.getStatus() != 1) {
            throw new RRException("站点不存在或非运营中: " + id);
        }
        return station;
    }

    private boolean cellBelongsToStation(Long cabinetId, Long stationId) {
        CabinetEntity cabinet = cabinetDao.selectById(cabinetId);
        return cabinet != null && stationId.equals(cabinet.getStationId());
    }

    private boolean casTask(Long id, TransferStatus from, TransferStatus to,
                            java.util.function.Consumer<LambdaUpdateWrapper<TransferTaskEntity>> extra) {
        LambdaUpdateWrapper<TransferTaskEntity> wrapper = new LambdaUpdateWrapper<TransferTaskEntity>()
                .eq(TransferTaskEntity::getId, id)
                .eq(TransferTaskEntity::getStatus, from.getCode())
                .set(TransferTaskEntity::getStatus, to.getCode())
                .set(TransferTaskEntity::getUpdateTime, System.currentTimeMillis());
        extra.accept(wrapper);
        return taskDao.update(null, wrapper) > 0;
    }

    private boolean casItem(Long id, TransferItemStatus from, TransferItemStatus to,
                            java.util.function.Consumer<LambdaUpdateWrapper<TransferTaskItemEntity>> extra) {
        LambdaUpdateWrapper<TransferTaskItemEntity> wrapper = new LambdaUpdateWrapper<TransferTaskItemEntity>()
                .eq(TransferTaskItemEntity::getId, id)
                .eq(TransferTaskItemEntity::getStatus, from.getCode())
                .set(TransferTaskItemEntity::getStatus, to.getCode())
                .set(TransferTaskItemEntity::getUpdateTime, System.currentTimeMillis());
        extra.accept(wrapper);
        return itemDao.update(null, wrapper) > 0;
    }
}
