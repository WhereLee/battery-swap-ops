package com.swapops.server.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 设备归属解析（S8 批次29）：设备号 → 归属站点 id。
 *
 * <p>背景：工单只有 {@code device_no}（形态不一：柜号 {@code SWAP-C-005}、仓级 {@code SWAP-C-005-3}、
 * 电池号 {@code BAT-0049}、woNo、{@code SITE-A}），而数据权限按站点隔离需要可过滤列——
 * 因此创建时解析一次并落 {@code work_order.station_id}（与 {@code swap_order.station_id} 同思路），
 * 避免每次查询跨三表解析。
 *
 * <p>语义：<b>解不出即返回 null</b>（不抛、不猜）。null 表示"无站点归属"（系统级/跨站级），
 * 受限身份按 fail-closed 规则看不到这类工单。
 */
@Slf4j
@Service
public class DeviceOwnershipService {

    private final CabinetDao cabinetDao;
    private final BatteryDao batteryDao;
    private final CellDao cellDao;

    public DeviceOwnershipService(CabinetDao cabinetDao, BatteryDao batteryDao, CellDao cellDao) {
        this.cabinetDao = cabinetDao;
        this.batteryDao = batteryDao;
        this.cellDao = cellDao;
    }

    /** 柜号 / 柜号-仓号 / 电池号 → 站点 id；无法归属返回 null。 */
    public Long resolveStationId(String deviceNo) {
        if (deviceNo == null || deviceNo.isBlank()) {
            return null;
        }
        String no = deviceNo.trim();
        Long byCabinet = stationIdOfCabinet(no);
        if (byCabinet != null) {
            return byCabinet;
        }
        // 仓级设备号形如 SWAP-C-005-3：截掉末段再试一次柜号
        int split = no.lastIndexOf('-');
        if (split > 0) {
            byCabinet = stationIdOfCabinet(no.substring(0, split));
            if (byCabinet != null) {
                return byCabinet;
            }
        }
        return stationIdOfBattery(no);
    }

    private Long stationIdOfCabinet(String cabinetNo) {
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo)
                .last("LIMIT 1"));
        return cabinet == null ? null : cabinet.getStationId();
    }

    /** 电池 → 所在仓 → 柜 → 站点（在途/无仓电池无归属，返回 null）。 */
    private Long stationIdOfBattery(String batteryNo) {
        BatteryEntity battery = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                .eq(BatteryEntity::getBatteryNo, batteryNo)
                .last("LIMIT 1"));
        if (battery == null || battery.getCellId() == null) {
            return null;
        }
        CellEntity cell = cellDao.selectById(battery.getCellId());
        if (cell == null || cell.getCabinetId() == null) {
            return null;
        }
        CabinetEntity cabinet = cabinetDao.selectById(cell.getCabinetId());
        return cabinet == null ? null : cabinet.getStationId();
    }
}
