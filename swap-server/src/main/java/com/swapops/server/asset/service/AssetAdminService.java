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
import com.swapops.server.asset.form.BatteryAdminForm;
import com.swapops.server.asset.form.CabinetAdminForm;
import com.swapops.server.asset.form.StationAdminForm;
import com.swapops.server.common.RRException;
import com.swapops.server.common.cache.CacheKeys;
import com.swapops.server.common.cache.TwoLevelCacheService;
import com.swapops.server.common.utils.PageParams;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.config.BatteryHealthProperties;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.service.BatteryCycleService;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.service.MonitorService;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.AllocationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private static final String ASSET_NO_PATTERN = "^[A-Za-z0-9-]{2,32}$";
    private static final String SECRET_PATTERN = "^[0-9a-fA-F]{32,64}$";
    private static final int MAX_CELLS = 48;
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
    private final TwoLevelCacheService cache;
    private final SwapOrderDao orderDao;
    private final DeviceChannelProperties properties;
    private final BatteryCycleService batteryCycleService;
    private final BatteryHealthProperties batteryHealthProperties;

    public AssetAdminService(StationDao stationDao, CabinetDao cabinetDao, CellDao cellDao,
                             BatteryDao batteryDao, MonitorService monitorService,
                             AllocationService allocationService, TwoLevelCacheService cache,
                             SwapOrderDao orderDao, DeviceChannelProperties properties,
                             BatteryCycleService batteryCycleService,
                             BatteryHealthProperties batteryHealthProperties) {
        this.stationDao = stationDao;
        this.cabinetDao = cabinetDao;
        this.cellDao = cellDao;
        this.batteryDao = batteryDao;
        this.monitorService = monitorService;
        this.allocationService = allocationService;
        this.cache = cache;
        this.orderDao = orderDao;
        this.properties = properties;
        this.batteryCycleService = batteryCycleService;
        this.batteryHealthProperties = batteryHealthProperties;
    }

    /** 电池健康档案（S4.1）：计数/分级/最近循环流水（可审计） */
    public Map<String, Object> batteryHealth(String batteryNo) {
        BatteryEntity battery = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                .eq(BatteryEntity::getBatteryNo, batteryNo));
        if (battery == null) {
            throw new RRException("电池不存在: " + batteryNo);
        }
        int soh = battery.getSoh() == null ? 100 : battery.getSoh();
        String level = soh >= batteryHealthProperties.getSohGoodThreshold() ? "GOOD"
                : soh >= batteryHealthProperties.getSohFairThreshold() ? "FAIR" : "POOR";
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("batteryNo", battery.getBatteryNo());
        view.put("model", battery.getModel());
        view.put("status", battery.getStatus());
        view.put("soc", battery.getSoc());
        view.put("soh", soh);
        view.put("healthLevel", level);
        view.put("cycleCount", battery.getCycleCount() == null ? 0 : battery.getCycleCount());
        view.put("swaps", battery.getSwaps() == null ? 0 : battery.getSwaps());
        view.put("cellId", battery.getCellId());
        view.put("holderUserId", battery.getHolderUserId());
        view.put("sohWarnThreshold", batteryHealthProperties.getSohWarnThreshold());
        view.put("recentCycles", batteryCycleService.recentLogs(batteryNo, 20));
        return view;
    }

    // ---------- 站点登记（S4.5 批二） ----------

    public StationEntity createStation(StationAdminForm form) {
        String stationNo = requirePattern(form.getStationNo(), "站点编号", ASSET_NO_PATTERN);
        if (stationDao.selectOne(new LambdaQueryWrapper<StationEntity>()
                .eq(StationEntity::getStationNo, stationNo)) != null) {
            throw new RRException("站点编号已存在: " + stationNo);
        }
        long now = System.currentTimeMillis();
        StationEntity station = new StationEntity();
        station.setStationNo(stationNo);
        station.setName(requireText(form.getName(), "站点名称", 32));
        station.setAddress(requireOptionalText(form.getAddress(), "站点地址", 128));
        station.setStatus(1);
        station.setCreateTime(now);
        station.setUpdateTime(now);
        stationDao.insert(station);
        cache.evict(CacheKeys.STATION_ACTIVE_LIST);
        log.info("站点创建 stationNo={} name={}", stationNo, station.getName());
        return station;
    }

    public StationEntity updateStation(Long id, StationAdminForm form) {
        StationEntity station = requireStation(id);
        if (form.getStationNo() != null && !form.getStationNo().isBlank()
                && !station.getStationNo().equals(form.getStationNo().trim())) {
            throw new RRException("站点编号不可变更: " + station.getStationNo());
        }
        station.setName(requireText(form.getName(), "站点名称", 32));
        station.setAddress(requireOptionalText(form.getAddress(), "站点地址", 128));
        station.setUpdateTime(System.currentTimeMillis());
        stationDao.updateById(station);
        cache.evict(CacheKeys.STATION_ACTIVE_LIST);
        log.info("站点更新 id={} name={}", id, station.getName());
        return stationDao.selectById(id);
    }

    public void deleteStation(Long id) {
        StationEntity station = requireStation(id);
        Long cabinets = cabinetDao.selectCount(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getStationId, id));
        if (cabinets != null && cabinets > 0) {
            throw new RRException("站点下仍有 " + cabinets + " 个柜，不能删除");
        }
        stationDao.deleteById(id);
        cache.evict(CacheKeys.STATION_ACTIVE_LIST);
        log.info("站点删除 id={} stationNo={}", id, station.getStationNo());
    }

    private StationEntity requireStation(Long id) {
        StationEntity station = stationDao.selectById(id);
        if (station == null) {
            throw new RRException("站点不存在: " + id);
        }
        return station;
    }

    // ---------- 柜登记（S4.5 批二；secret 响应脱敏） ----------

    @Transactional
    public CabinetEntity createCabinet(CabinetAdminForm form) {
        String cabinetNo = requirePattern(form.getCabinetNo(), "柜编号", ASSET_NO_PATTERN);
        if (cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo)) != null) {
            throw new RRException("柜编号已存在: " + cabinetNo);
        }
        StationEntity station = requireStation(form.getStationId());
        int cellCount = requireCellCount(form.getCellCount());
        String secret = requirePattern(form.getSecret(), "设备密钥", SECRET_PATTERN);
        long now = System.currentTimeMillis();
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setCabinetNo(cabinetNo);
        cabinet.setStationId(station.getId());
        cabinet.setCellCount(cellCount);
        cabinet.setStatus(CabinetStatus.ONLINE.getCode());
        cabinet.setSecret(secret);
        cabinet.setCreateTime(now);
        cabinet.setUpdateTime(now);
        cabinetDao.insert(cabinet);
        for (int cellNo = 1; cellNo <= cellCount; cellNo++) {
            CellEntity cell = new CellEntity();
            cell.setCabinetId(cabinet.getId());
            cell.setCellNo(cellNo);
            cell.setStatus(CellStatus.EMPTY.getCode());
            cell.setUpdateTime(now);
            cellDao.insert(cell);
        }
        allocationService.rebuildFromDb();
        log.info("柜创建 cabinetNo={} stationId={} cells={}", cabinetNo, station.getId(), cellCount);
        return maskSecret(cabinet);
    }

    @Transactional
    public CabinetEntity updateCabinet(Long id, CabinetAdminForm form) {
        CabinetEntity cabinet = cabinetDao.selectById(id);
        if (cabinet == null) {
            throw new RRException("柜不存在: " + id);
        }
        if (form.getStationId() != null && !form.getStationId().equals(cabinet.getStationId())) {
            StationEntity station = requireStation(form.getStationId());
            cabinet.setStationId(station.getId());
        }
        if (form.getSecret() != null && !form.getSecret().isBlank()) {
            cabinet.setSecret(requirePattern(form.getSecret(), "设备密钥", SECRET_PATTERN));
        }
        if (form.getCellCount() != null && !form.getCellCount().equals(cabinet.getCellCount())) {
            adjustCells(cabinet, requireCellCount(form.getCellCount()));
        }
        cabinet.setUpdateTime(System.currentTimeMillis());
        cabinetDao.updateById(cabinet);
        allocationService.rebuildFromDb();
        log.info("柜更新 id={} cabinetNo={} cells={}", id, cabinet.getCabinetNo(), cabinet.getCellCount());
        return maskSecret(cabinetDao.selectById(id));
    }

    @Transactional
    public void deleteCabinet(Long id) {
        CabinetEntity cabinet = cabinetDao.selectById(id);
        if (cabinet == null) {
            throw new RRException("柜不存在: " + id);
        }
        List<CellEntity> cells = cellDao.selectList(new LambdaQueryWrapper<CellEntity>()
                .eq(CellEntity::getCabinetId, id));
        for (CellEntity cell : cells) {
            if (cell.getBatteryId() != null || cell.getLockOrderId() != null) {
                throw new RRException("柜内仍有电池或锁定的仓（cellNo=" + cell.getCellNo() + "），不能删除");
            }
        }
        cellDao.delete(new LambdaQueryWrapper<CellEntity>().eq(CellEntity::getCabinetId, id));
        cabinetDao.deleteById(id);
        allocationService.rebuildFromDb();
        log.info("柜删除 id={} cabinetNo={}", id, cabinet.getCabinetNo());
    }

    /** 增减仓：增加补建；减少仅允许尾部"无电池且无锁"的仓 */
    private void adjustCells(CabinetEntity cabinet, int newCount) {
        List<CellEntity> cells = cellDao.selectList(new LambdaQueryWrapper<CellEntity>()
                .eq(CellEntity::getCabinetId, cabinet.getId())
                .orderByAsc(CellEntity::getCellNo));
        int current = cells.size();
        if (newCount > current) {
            long now = System.currentTimeMillis();
            for (int cellNo = current + 1; cellNo <= newCount; cellNo++) {
                CellEntity cell = new CellEntity();
                cell.setCabinetId(cabinet.getId());
                cell.setCellNo(cellNo);
                cell.setStatus(CellStatus.EMPTY.getCode());
                cell.setUpdateTime(now);
                cellDao.insert(cell);
            }
        } else if (newCount < current) {
            for (CellEntity cell : cells) {
                if (cell.getCellNo() > newCount
                        && (cell.getBatteryId() != null || cell.getLockOrderId() != null)) {
                    throw new RRException("缩减失败：cellNo=" + cell.getCellNo() + " 仍有电池或锁");
                }
            }
            cellDao.delete(new LambdaQueryWrapper<CellEntity>()
                    .eq(CellEntity::getCabinetId, cabinet.getId())
                    .gt(CellEntity::getCellNo, newCount));
        }
        cabinet.setCellCount(newCount);
    }

    private CabinetEntity maskSecret(CabinetEntity cabinet) {
        cabinet.setSecret(null);
        return cabinet;
    }

    private int requireCellCount(Integer cellCount) {
        if (cellCount == null || cellCount < 1 || cellCount > MAX_CELLS) {
            throw new RRException("仓位数需在 1~" + MAX_CELLS + " 之间");
        }
        return cellCount;
    }

    // ---------- 电池登记（S4.5 批二） ----------

    @Transactional
    public BatteryEntity createBattery(BatteryAdminForm form) {
        String batteryNo = requirePattern(form.getBatteryNo(), "电池编号", ASSET_NO_PATTERN);
        if (batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                .eq(BatteryEntity::getBatteryNo, batteryNo)) != null) {
            throw new RRException("电池编号已存在: " + batteryNo);
        }
        int soc = requireRange(form.getSoc(), "SOC", 0, 100, 100);
        int soh = requireRange(form.getSoh(), "SOH", 0, 100, 100);
        int cycles = form.getCycleCount() == null ? 0 : form.getCycleCount();
        if (cycles < 0) {
            throw new RRException("循环次数不能为负");
        }
        long now = System.currentTimeMillis();
        BatteryEntity battery = new BatteryEntity();
        battery.setBatteryNo(batteryNo);
        String model = requireOptionalText(form.getModel(), "电池型号", 32);
        battery.setModel(model == null ? "48V24Ah" : model);
        battery.setSoc(soc);
        battery.setSoh(soh);
        battery.setCycleCount(cycles);
        battery.setHolderUserId(null);
        battery.setUpdateTime(now);
        if (form.getCellId() != null) {
            CellEntity cell = requireEmptyCell(form.getCellId());
            battery.setCellId(cell.getId());
            battery.setStatus(soc >= properties.getSocFullThreshold()
                    ? BatteryStatus.FULL.getCode() : BatteryStatus.CHARGING.getCode());
            batteryDao.insert(battery);
            cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                    .eq(CellEntity::getId, cell.getId())
                    .set(CellEntity::getBatteryId, battery.getId())
                    .set(CellEntity::getStatus, CellStatus.OCCUPIED.getCode())
                    .set(CellEntity::getUpdateTime, now));
        } else {
            battery.setCellId(null);
            battery.setStatus(BatteryStatus.CHARGING.getCode());
            batteryDao.insert(battery);
        }
        allocationService.rebuildFromDb();
        log.info("电池登记 batteryNo={} cellId={} soc={}", batteryNo, form.getCellId(), soc);
        return battery;
    }

    @Transactional
    public BatteryEntity updateBattery(String batteryNo, BatteryAdminForm form) {
        BatteryEntity battery = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                .eq(BatteryEntity::getBatteryNo, batteryNo));
        if (battery == null) {
            throw new RRException("电池不存在: " + batteryNo);
        }
        if (battery.getHolderUserId() != null) {
            throw new RRException("电池在用户手中，请走归还流程后再编辑: " + batteryNo);
        }
        long now = System.currentTimeMillis();
        if (form.getModel() != null && !form.getModel().isBlank()) {
            battery.setModel(requireOptionalText(form.getModel(), "电池型号", 32));
        }
        if (form.getSoc() != null) {
            battery.setSoc(requireRange(form.getSoc(), "SOC", 0, 100, battery.getSoc()));
        }
        if (form.getSoh() != null) {
            battery.setSoh(requireRange(form.getSoh(), "SOH", 0, 100, battery.getSoh()));
        }
        if (form.getCycleCount() != null) {
            if (form.getCycleCount() < 0) {
                throw new RRException("循环次数不能为负");
            }
            battery.setCycleCount(form.getCycleCount());
        }
        if (form.getCellId() != null) {
            moveToCell(battery, form.getCellId(), now);
        } else if (Boolean.TRUE.equals(form.getPark()) && battery.getCellId() != null) {
            Long oldCellId = battery.getCellId();
            // 先持久化标量编辑（此时 cellId 仍为原值；updateById 不会误将非空字段置空）
            battery.setUpdateTime(now);
            batteryDao.updateById(battery);
            clearCell(oldCellId, now);
            // 注意：updateById 默认忽略 null 字段——置空 cell_id 必须走显式 set 的 UPDATE
            battery.setCellId(null);
            battery.setStatus(BatteryStatus.CHARGING.getCode());
            batteryDao.update(null, new LambdaUpdateWrapper<BatteryEntity>()
                    .eq(BatteryEntity::getId, battery.getId())
                    .set(BatteryEntity::getCellId, null)
                    .set(BatteryEntity::getStatus, BatteryStatus.CHARGING.getCode())
                    .set(BatteryEntity::getUpdateTime, now));
            allocationService.rebuildFromDb();
            log.info("电池转在途 batteryNo={}（含标量编辑落库）", batteryNo);
            return batteryDao.selectById(battery.getId());
        } else if (battery.getCellId() != null) {
            // 留在原仓：状态随 SOC 修正
            battery.setStatus(battery.getSoc() != null && battery.getSoc() >= properties.getSocFullThreshold()
                    ? BatteryStatus.FULL.getCode() : BatteryStatus.CHARGING.getCode());
        }
        battery.setUpdateTime(now);
        batteryDao.updateById(battery);
        allocationService.rebuildFromDb();
        log.info("电池更新 batteryNo={} cellId={} soc={}", batteryNo, battery.getCellId(), battery.getSoc());
        return batteryDao.selectById(battery.getId());
    }

    @Transactional
    public void deleteBattery(String batteryNo) {
        BatteryEntity battery = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                .eq(BatteryEntity::getBatteryNo, batteryNo));
        if (battery == null) {
            throw new RRException("电池不存在: " + batteryNo);
        }
        if (battery.getCellId() != null || battery.getHolderUserId() != null) {
            throw new RRException("仅在途且无持有人的电池可删除: " + batteryNo);
        }
        Long refs = orderDao.selectCount(new LambdaQueryWrapper<SwapOrderEntity>()
                .and(w -> w.eq(SwapOrderEntity::getTakeBatteryId, battery.getId())
                        .or().eq(SwapOrderEntity::getReturnBatteryId, battery.getId())));
        if (refs != null && refs > 0) {
            throw new RRException("电池已被订单引用（" + refs + " 次），建议改为退役");
        }
        batteryDao.deleteById(battery.getId());
        allocationService.rebuildFromDb();
        log.info("电池删除 batteryNo={}", batteryNo);
    }

    private void moveToCell(BatteryEntity battery, Long cellId, long now) {
        if (cellId.equals(battery.getCellId())) {
            return;
        }
        CellEntity target = requireEmptyCell(cellId);
        if (battery.getCellId() != null) {
            clearCell(battery.getCellId(), now);
        }
        battery.setCellId(target.getId());
        battery.setStatus(battery.getSoc() != null && battery.getSoc() >= properties.getSocFullThreshold()
                ? BatteryStatus.FULL.getCode() : BatteryStatus.CHARGING.getCode());
        cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                .eq(CellEntity::getId, target.getId())
                .set(CellEntity::getBatteryId, battery.getId())
                .set(CellEntity::getStatus, CellStatus.OCCUPIED.getCode())
                .set(CellEntity::getUpdateTime, now));
    }

    private void clearCell(Long cellId, long now) {
        cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                .eq(CellEntity::getId, cellId)
                .set(CellEntity::getBatteryId, null)
                .set(CellEntity::getStatus, CellStatus.EMPTY.getCode())
                .set(CellEntity::getUpdateTime, now));
    }

    private CellEntity requireEmptyCell(Long cellId) {
        CellEntity cell = cellDao.selectById(cellId);
        if (cell == null) {
            throw new RRException("目标仓不存在: " + cellId);
        }
        if (cell.getBatteryId() != null || cell.getLockOrderId() != null) {
            throw new RRException("目标仓非空或已锁定: cellId=" + cellId);
        }
        return cell;
    }

    private int requireRange(Integer value, String label, int min, int max, Integer defaultValue) {
        int v = value == null ? (defaultValue == null ? min : defaultValue) : value;
        if (v < min || v > max) {
            throw new RRException(label + " 需在 " + min + "~" + max + " 之间");
        }
        return v;
    }

    private String requirePattern(String value, String label, String pattern) {
        String v = value == null ? "" : value.trim();
        if (!v.matches(pattern)) {
            throw new RRException(label + " 格式非法: " + value);
        }
        return v;
    }

    private String requireText(String value, String label, int maxLength) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            throw new RRException(label + "必填");
        }
        if (v.length() > maxLength) {
            throw new RRException(label + "过长（<=" + maxLength + "）");
        }
        return v;
    }

    /** 可选文本：空=null；非空则 trim 并校验长度（超长拒绝，不静默截断） */
    private String requireOptionalText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > maxLength) {
            throw new RRException(label + "过长（<=" + maxLength + "）");
        }
        return v;
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
        // 写路径失效：站点元数据缓存（含其他实例 L1 的 Pub/Sub 广播）
        cache.evict(CacheKeys.STATION_ACTIVE_LIST);
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

    /** 站点列表（含可换满电数/可还仓位数；与分配同源，运营口径一致；站点元数据走两级缓存，实时计数不缓存） */
    public List<Map<String, Object>> listUserStations() {
        List<StationEntity> stations = cache.getList(CacheKeys.STATION_ACTIVE_LIST, StationEntity.class,
                () -> stationDao.selectList(new LambdaQueryWrapper<StationEntity>()
                        .eq(StationEntity::getStatus, 1).orderByAsc(StationEntity::getId)));
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
