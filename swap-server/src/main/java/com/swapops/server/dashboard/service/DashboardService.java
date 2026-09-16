package com.swapops.server.dashboard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.contract.BatteryStatus;
import com.swapops.contract.OrderStatus;
import com.swapops.server.admin.data.DataScopeSupport;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.asset.entity.StationEntity;
import com.swapops.server.common.cache.CacheKeys;
import com.swapops.server.common.cache.TwoLevelCacheService;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.AllocationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运营看板（S4.5 批一）：
 * 满电保有率、站点可用率、电池周转率——口径固定、聚合结果走两级缓存（短 TTL 60s）。
 * 实时计数以分配池（Redis，与分配同源）为准；电池/订单口径走 DB 事实源。
 */
@Slf4j
@Service
public class DashboardService {

    /** 聚合缓存 TTL（秒）：看板允许秒级陈旧，避免每次刷新压 DB */
    static final int CACHE_TTL_SECONDS = 60;

    private final BatteryDao batteryDao;
    private final StationDao stationDao;
    private final CabinetDao cabinetDao;
    private final CellDao cellDao;
    private final SwapOrderDao orderDao;
    private final AllocationService allocationService;
    private final TwoLevelCacheService cache;

    public DashboardService(BatteryDao batteryDao, StationDao stationDao, CabinetDao cabinetDao,
                            CellDao cellDao, SwapOrderDao orderDao, AllocationService allocationService,
                            TwoLevelCacheService cache) {
        this.batteryDao = batteryDao;
        this.stationDao = stationDao;
        this.cabinetDao = cabinetDao;
        this.cellDao = cellDao;
        this.orderDao = orderDao;
        this.allocationService = allocationService;
        this.cache = cache;
    }

    /** 看板总览（缓存 60s；缓存不可用自动直算；P1-8：受限身份不共享全局缓存，直算本域口径） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> overview() {
        if (DataScopeSupport.stationIdsOrNull() != null) {
            return compute();
        }
        Map<String, Object> cached = cache.getEntity(CacheKeys.DASHBOARD_OVERVIEW, Map.class,
                this::compute, CACHE_TTL_SECONDS);
        return cached == null ? compute() : cached;
    }

    Map<String, Object> compute() {
        Set<Long> scopeCellIds = DataScopeSupport.cellIdsOrNull(cabinetDao, cellDao); // P1-8：本域仓 id 集（null=不受限）
        LambdaQueryWrapper<BatteryEntity> activeWrapper = new LambdaQueryWrapper<BatteryEntity>()
                .ne(BatteryEntity::getStatus, BatteryStatus.RETIRED.getCode());
        DataScopeSupport.applyIds(activeWrapper, BatteryEntity::getCellId, scopeCellIds);
        long activeBatteries = count(batteryDao.selectCount(activeWrapper));
        LambdaQueryWrapper<BatteryEntity> fullWrapper = new LambdaQueryWrapper<BatteryEntity>()
                .eq(BatteryEntity::getStatus, BatteryStatus.FULL.getCode());
        DataScopeSupport.applyIds(fullWrapper, BatteryEntity::getCellId, scopeCellIds);
        long fullBatteries = count(batteryDao.selectCount(fullWrapper));

        LambdaQueryWrapper<StationEntity> stationWrapper = new LambdaQueryWrapper<StationEntity>()
                .eq(StationEntity::getStatus, 1);
        DataScopeSupport.applyStation(stationWrapper, StationEntity::getId); // P1-8
        List<StationEntity> stations = stationDao.selectList(stationWrapper);
        int availableStations = 0;
        List<String> unavailableStations = new ArrayList<>();
        for (StationEntity station : stations) {
            List<CabinetEntity> cabinets = cabinetDao.selectList(new LambdaQueryWrapper<CabinetEntity>()
                    .eq(CabinetEntity::getStationId, station.getId()));
            long fullCount = 0;
            for (CabinetEntity cabinet : cabinets) {
                fullCount += allocationService.countAvailable(cabinet.getCabinetNo(), true);
            }
            if (fullCount > 0) {
                availableStations++;
            } else {
                unavailableStations.add(station.getStationNo());
            }
        }

        long startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        LambdaQueryWrapper<SwapOrderEntity> orderWrapper = new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getStatus, OrderStatus.COMPLETED.getCode())
                .ge(SwapOrderEntity::getCompleteTime, startOfDay);
        DataScopeSupport.applyStation(orderWrapper, SwapOrderEntity::getStationId); // P1-8
        long completedToday = count(orderDao.selectCount(orderWrapper));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", System.currentTimeMillis());
        result.put("totalBatteries", activeBatteries);
        result.put("fullBatteries", fullBatteries);
        result.put("fullBatteryRate", ratio(fullBatteries, activeBatteries));
        result.put("totalStations", stations.size());
        result.put("availableStations", availableStations);
        result.put("stationAvailabilityRate", ratio(availableStations, stations.size()));
        result.put("completedToday", completedToday);
        result.put("turnoverRate", activeBatteries == 0 ? 0.0 : round4((double) completedToday / activeBatteries));
        result.put("unavailableStations", unavailableStations);
        log.info("[看板] 总览计算 totalBatteries={} full={} stations={} available={} completedToday={}",
                activeBatteries, fullBatteries, stations.size(), availableStations, completedToday);
        return result;
    }

    private long count(Long value) {
        return value == null ? 0 : value;
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : round4((double) numerator / denominator);
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
