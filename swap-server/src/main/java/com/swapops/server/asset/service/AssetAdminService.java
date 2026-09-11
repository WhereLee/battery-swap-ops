package com.swapops.server.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.swapops.contract.BatteryStatus;
import com.swapops.contract.CabinetStatus;
import com.swapops.contract.CellStatus;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.asset.entity.StationEntity;
import com.swapops.server.common.RRException;
import com.swapops.server.common.utils.PageParams;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.service.MonitorService;
import com.swapops.server.order.service.AllocationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资产域运营接口（S2.1 口径：查询 + 维护态操作；登记类 CRUD 归 S4）：
 * 站点/柜/仓/电池的检索与"维护/停用/退役/恢复"状态运维；状态变更同步分配集合。
 */
@Slf4j
@Service
public class AssetAdminService {

    private static final Set<Integer> STATION_STATUS = Set.of(1, 2);
    private static final Set<Integer> CABINET_ADMIN_STATUS = Set.of(
            CabinetStatus.ONLINE.getCode(), CabinetStatus.MAINTENANCE.getCode(), CabinetStatus.DISABLED.getCode());
    private static final Set<Integer> CELL_ADMIN_STATUS = Set.of(
            CellStatus.FAULT.getCode(), CellStatus.DISABLED.getCode(), 0);

    private final StationDao stationDao;
    private final CabinetDao cabinetDao;
    private final CellDao cellDao;
    private final BatteryDao batteryDao;
    private final MonitorService monitorService;
    private final AllocationService allocationService;

    public AssetAdminService(StationDao stationDao, CabinetDao cabinetDao, CellDao cellDao,
                             BatteryDao batteryDao, MonitorService monitorService,
                             AllocationService allocationService) {
        this.stationDao = stationDao;
        this.cabinetDao = cabinetDao;
        this.cellDao = cellDao;
        this.batteryDao = batteryDao;
        this.monitorService = monitorService;
        this.allocationService = allocationService;
    }

    // ---------- 站点 ----------

    public PageResult<StationEntity> pageStations(Integer page, Integer limit, Integer status) {
        int pageNum = PageParams.page(page);
        int size = PageParams.limit(limit);
        IPage<StationEntity> result = stationDao.selectPage(new Page<>(pageNum, size),
                new LambdaQueryWrapper<StationEntity>()
                        .eq(status != null, StationEntity::getStatus, status)
                        .orderByAsc(StationEntity::getId));
        return PageResult.of(result);
    }

    public void updateStationStatus(Long id, Integer status) {
        requireIn(status, STATION_STATUS, "站点状态可选 1 运营 / 2 停用");
        StationEntity station = stationDao.selectById(id);
        if (station == null) {
            throw new RRException("站点不存在: " + id);
        }
        stationDao.update(null, new LambdaUpdateWrapper<StationEntity>()
                .eq(StationEntity::getId, id)
                .set(StationEntity::getStatus, status)
                .set(StationEntity::getUpdateTime, System.currentTimeMillis()));
        log.info("站点状态变更 stationNo={} status={}", station.getStationNo(), status);
    }

    // ---------- 柜 ----------

    public PageResult<CabinetEntity> pageCabinets(Integer page, Integer limit, Long stationId,
                                                  Integer status, String cabinetNo) {
        int pageNum = PageParams.page(page);
        int size = PageParams.limit(limit);
        IPage<CabinetEntity> result = cabinetDao.selectPage(new Page<>(pageNum, size),
                new LambdaQueryWrapper<CabinetEntity>()
                        .eq(stationId != null, CabinetEntity::getStationId, stationId)
                        .eq(status != null, CabinetEntity::getStatus, status)
                        .like(cabinetNo != null && !cabinetNo.isBlank(), CabinetEntity::getCabinetNo, cabinetNo)
                        .orderByAsc(CabinetEntity::getId));
        return PageResult.of(result);
    }

    /** 柜实况：档案 + 仓/电池快照 + 在线态（运维排障入口） */
    public Map<String, Object> cabinetState(String cabinetNo) {
        CabinetEntity cabinet = requireCabinet(cabinetNo);
        List<CellEntity> cells = cellDao.selectList(new LambdaQueryWrapper<CellEntity>()
                .eq(CellEntity::getCabinetId, cabinet.getId())
                .orderByAsc(CellEntity::getCellNo));
        List<Map<String, Object>> cellViews = new ArrayList<>();
        for (CellEntity cell : cells) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("cellNo", cell.getCellNo());
            view.put("status", cell.getStatus());
            view.put("lockOrderId", cell.getLockOrderId());
            if (cell.getBatteryId() != null) {
                BatteryEntity battery = batteryDao.selectById(cell.getBatteryId());
                if (battery != null) {
                    view.put("batteryNo", battery.getBatteryNo());
                    view.put("batteryStatus", battery.getStatus());
                    view.put("soc", battery.getSoc());
                }
            }
            cellViews.add(view);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cabinetNo", cabinet.getCabinetNo());
        data.put("stationId", cabinet.getStationId());
        data.put("status", cabinet.getStatus());
        data.put("online", monitorService.isOnline(cabinetNo));
        data.put("lastBootId", cabinet.getLastBootId());
        data.put("lastEventSeq", cabinet.getLastEventSeq());
        data.put("lastHeartbeatTime", cabinet.getLastHeartbeatTime());
        data.put("cells", cellViews);
        return data;
    }

    public void updateCabinetStatus(String cabinetNo, Integer status) {
        requireIn(status, CABINET_ADMIN_STATUS, "柜状态可选 1 在线 / 4 维护 / 5 停用");
        requireCabinet(cabinetNo);
        cabinetDao.update(null, new LambdaUpdateWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo)
                .set(CabinetEntity::getStatus, status)
                .set(CabinetEntity::getUpdateTime, System.currentTimeMillis()));
        allocationService.rebuildFromDb();
        log.info("柜状态变更 cabinetNo={} status={}（分配集合已重建）", cabinetNo, status);
    }

    // ---------- 仓 ----------

    public PageResult<CellEntity> pageCells(Integer page, Integer limit, String cabinetNo, Integer status) {
        int pageNum = PageParams.page(page);
        int size = PageParams.limit(limit);
        Long cabinetId = cabinetNo == null || cabinetNo.isBlank() ? null : requireCabinet(cabinetNo).getId();
        IPage<CellEntity> result = cellDao.selectPage(new Page<>(pageNum, size),
                new LambdaQueryWrapper<CellEntity>()
                        .eq(cabinetId != null, CellEntity::getCabinetId, cabinetId)
                        .eq(status != null, CellEntity::getStatus, status)
                        .orderByAsc(CellEntity::getCabinetId).orderByAsc(CellEntity::getCellNo));
        return PageResult.of(result);
    }

    /**
     * 仓位状态运维：3 故障 / 4 停用 / 0 恢复（按电池有无推断 EMPTY/OCCUPIED）。
     * 恢复不允许把有电池的仓标为空——状态以电池字段为准。
     */
    public void updateCellStatus(Long cellId, Integer status) {
        requireIn(status, CELL_ADMIN_STATUS, "仓状态可选 3 故障 / 4 停用 / 0 恢复");
        CellEntity cell = cellDao.selectById(cellId);
        if (cell == null) {
            throw new RRException("仓不存在: " + cellId);
        }
        int target;
        if (status == 0) {
            target = cell.getBatteryId() == null ? CellStatus.EMPTY.getCode() : CellStatus.OCCUPIED.getCode();
        } else {
            target = status;
        }
        cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                .eq(CellEntity::getId, cellId)
                .set(CellEntity::getStatus, target)
                .set(CellEntity::getUpdateTime, System.currentTimeMillis()));
        allocationService.refreshByCellId(cellId);
        log.info("仓状态变更 cellId={} status={}（分配集合已刷新）", cellId, target);
    }

    // ---------- 电池 ----------

    public PageResult<BatteryEntity> pageBatteries(Integer page, Integer limit, Integer status, String batteryNo) {
        int pageNum = PageParams.page(page);
        int size = PageParams.limit(limit);
        IPage<BatteryEntity> result = batteryDao.selectPage(new Page<>(pageNum, size),
                new LambdaQueryWrapper<BatteryEntity>()
                        .eq(status != null, BatteryEntity::getStatus, status)
                        .like(batteryNo != null && !batteryNo.isBlank(), BatteryEntity::getBatteryNo, batteryNo)
                        .orderByAsc(BatteryEntity::getId));
        return PageResult.of(result);
    }

    /**
     * 电池状态运维：4 维修 / 5 退役 / 0 恢复（恢复需电池在仓 → CHARGING；不在仓拒绝恢复在仓态）。
     */
    public void updateBatteryStatus(String batteryNo, Integer status) {
        if (status == null || !(status == 4 || status == 5 || status == 0)) {
            throw new RRException("电池状态可选 4 维修 / 5 退役 / 0 恢复");
        }
        BatteryEntity battery = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                .eq(BatteryEntity::getBatteryNo, batteryNo));
        if (battery == null) {
            throw new RRException("电池不存在: " + batteryNo);
        }
        int target;
        if (status == 0) {
            if (battery.getCellId() == null) {
                throw new RRException("电池不在仓，无法恢复在仓态（借出电池需走归还流程）: " + batteryNo);
            }
            target = BatteryStatus.CHARGING.getCode();
        } else {
            target = status;
        }
        batteryDao.update(null, new LambdaUpdateWrapper<BatteryEntity>()
                .eq(BatteryEntity::getId, battery.getId())
                .set(BatteryEntity::getStatus, target)
                .set(BatteryEntity::getUpdateTime, System.currentTimeMillis()));
        allocationService.refreshByBatteryId(battery.getId());
        log.info("电池状态变更 batteryNo={} status={}（分配集合已刷新）", batteryNo, target);
    }

    // ---------- 用户端站点列表 ----------

    /** 站点列表（含可换满电数/可还仓位数；与分配同源，运营口径一致） */
    public List<Map<String, Object>> listUserStations() {
        List<StationEntity> stations = stationDao.selectList(new LambdaQueryWrapper<StationEntity>()
                .eq(StationEntity::getStatus, 1).orderByAsc(StationEntity::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (StationEntity station : stations) {
            List<CabinetEntity> cabinets = cabinetDao.selectList(new LambdaQueryWrapper<CabinetEntity>()
                    .eq(CabinetEntity::getStationId, station.getId()));
            long fullCount = 0;
            long emptyCount = 0;
            for (CabinetEntity cabinet : cabinets) {
                fullCount += allocationService.countAvailable(cabinet.getCabinetNo(), true);
                emptyCount += allocationService.countAvailable(cabinet.getCabinetNo(), false);
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("stationNo", station.getStationNo());
            view.put("name", station.getName());
            view.put("address", station.getAddress());
            view.put("fullBatteryCount", fullCount);
            view.put("emptyCellCount", emptyCount);
            result.add(view);
        }
        return result;
    }

    public CabinetEntity requireCabinet(String cabinetNo) {
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo));
        if (cabinet == null) {
            throw new RRException("柜不存在: " + cabinetNo);
        }
        return cabinet;
    }

    private void requireIn(Integer value, Set<Integer> allowed, String message) {
        if (value == null || !allowed.contains(value)) {
            throw new RRException(message);
        }
    }
}
