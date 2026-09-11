package com.swapops.server.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.contract.BatteryStatus;
import com.swapops.contract.CellStatus;
import com.swapops.contract.CommandAction;
import com.swapops.contract.EventType;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.common.RRException;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.config.DeviceBootGenerationGuard;
import com.swapops.server.device.form.DeviceEventForm;
import com.swapops.server.order.service.AllocationService;
import com.swapops.server.order.service.OrderEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备事件编排（协议 v1）：
 * 必填校验 → 序守卫（bootId/eventSeq 条件 UPDATE）→ 事件语义（仓/电池状态推进）→ 指令销账。
 * 事件是唯一事实源；重复/乱序事件被序守卫幂等丢弃；处理不做"半截子"。
 */
@Slf4j
@Service
public class DeviceEventService {

    private final CabinetDao cabinetDao;
    private final CellDao cellDao;
    private final BatteryDao batteryDao;
    private final CommandLogService commandLogService;
    private final DeviceChannelProperties properties;
    private final AllocationService allocationService;
    private final OrderEventService orderEventService;
    private final DeviceBootGenerationGuard bootGenerationGuard;
    private final AlarmService alarmService;

    public DeviceEventService(CabinetDao cabinetDao, CellDao cellDao, BatteryDao batteryDao,
                              CommandLogService commandLogService, DeviceChannelProperties properties,
                              AllocationService allocationService, OrderEventService orderEventService,
                              DeviceBootGenerationGuard bootGenerationGuard, AlarmService alarmService) {
        this.cabinetDao = cabinetDao;
        this.cellDao = cellDao;
        this.batteryDao = batteryDao;
        this.commandLogService = commandLogService;
        this.properties = properties;
        this.allocationService = allocationService;
        this.orderEventService = orderEventService;
        this.bootGenerationGuard = bootGenerationGuard;
        this.alarmService = alarmService;
    }

    @Transactional
    public boolean handle(DeviceEventForm form) {
        // 1. 必填校验（协议垃圾快速失败；authenticator 已验签）
        EventType type;
        try {
            type = EventType.fromWire(form.getEventType());
        } catch (IllegalArgumentException e) {
            throw new RRException("未知事件类型: " + form.getEventType());
        }
        String cabinetNo = form.getCabinetNo();
        String bootId = form.getBootId();
        Long eventSeq = form.getEventSeq();
        if (cabinetNo == null || cabinetNo.isEmpty()
                || bootId == null || bootId.isEmpty()
                || eventSeq == null) {
            throw new RRException("事件缺必填字段(cabinetNo/eventType/bootId/eventSeq)");
        }

        // 1.5 跨代际重放守卫（S3.1）：序守卫对 bootId 变化一律接受，旧代际事件重放会借"新代际"过关——
        // 历史已见的代际在此拒绝（DB 序守卫仍守同代际乱序/重复，两层互补）
        if (bootGenerationGuard.isReplay(cabinetNo, bootId)) {
            log.warn("柜代际重放拒绝（已见代际事件重放，不推进台账/流水/订单） cabinetNo={} bootId={} eventSeq={} eventType={}",
                    cabinetNo, bootId, eventSeq, type);
            return false;
        }

        // 2. 序守卫（单条条件 UPDATE：守卫+推进基线原子完成）
        long now = System.currentTimeMillis();
        int rows = cabinetDao.update(null, new LambdaUpdateWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo)
                .set(CabinetEntity::getLastBootId, bootId)
                .set(CabinetEntity::getLastEventSeq, eventSeq)
                .set(CabinetEntity::getUpdateTime, now)
                .and(w -> w.isNull(CabinetEntity::getLastBootId)
                        .or().ne(CabinetEntity::getLastBootId, bootId)
                        .or(o -> o.eq(CabinetEntity::getLastBootId, bootId)
                                .lt(CabinetEntity::getLastEventSeq, eventSeq))));
        if (rows == 0) {
            CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                    .eq(CabinetEntity::getCabinetNo, cabinetNo));
            if (cabinet == null) {
                throw new RRException("未登记的柜事件: " + cabinetNo);
            }
            log.info("事件序守卫拒绝(同代际旧序/重放, 幂等丢弃) cabinetNo={} bootId={} eventSeq={} 已受理last={}/{}",
                    cabinetNo, bootId, eventSeq, cabinet.getLastBootId(), cabinet.getLastEventSeq());
            return false;
        }

        // 序守卫接受：登记代际（供后续重放判定；新代际首见，同代际幂等）
        bootGenerationGuard.register(cabinetNo, bootId);

        // 3. 事件语义（柜档案一次加载，供仓定位与日志使用）
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo));
        if (cabinet == null) {
            throw new RRException("未登记的柜事件: " + cabinetNo);
        }
        apply(type, form, cabinet);
        log.info("事件已受理 cabinetNo={} eventType={} cellNo={} batteryNo={} bootId={} eventSeq={}",
                cabinetNo, type, form.getCellNo(), form.getBatteryNo(), bootId, eventSeq);
        return true;
    }

    private void apply(EventType type, DeviceEventForm form, CabinetEntity cabinet) {
        switch (type) {
            case DOOR_OPENED -> {
                log.info("门已开 cabinetNo={} cellNo={} commandSeq={}",
                        form.getCabinetNo(), form.getCellNo(), form.getCommandSeq());
                if (form.getCommandSeq() != null) {
                    commandLogService.markArrivedBySeq(form.getCabinetNo(), form.getCommandSeq(),
                            CommandAction.OPEN_CELL);
                }
                // 订单域：门开推进 PENDING_OPEN → OPENED（无 commandSeq 只走设备台账）
                orderEventService.onDoorOpened(cabinet, form.getCellNo(), form.getCommandSeq());
            }
            case BATTERY_OUT -> {
                CellEntity cell = requireCell(cabinet, form);
                BatteryEntity battery = requireBattery(form);
                if (cell.getBatteryId() != null && !cell.getBatteryId().equals(battery.getId())) {
                    // 事件是唯一事实源：仍按事件执行，但台账不一致必须留痕（S3 对账的输入）
                    log.warn("台账不一致：取出电池与仓内登记不符 cabinetNo={} cellNo={} 台账={} 事件={}",
                            form.getCabinetNo(), form.getCellNo(), cell.getBatteryId(), battery.getId());
                }
                updateCell(cell.getId(), CellStatus.EMPTY, null);
                detachBattery(battery.getId());
                // 分配集合：仓变空 + 预占锁随订单推进清除；订单域按会话 seq 推进（TAKE 完成 / SWAP 待还）
                allocationService.onBatteryOut(cell.getId());
                orderEventService.onBatteryOut(cabinet, cell, battery, form.getCommandSeq());
                log.info("取电 cabinetNo={} cellNo={} batteryNo={}", form.getCabinetNo(), form.getCellNo(), form.getBatteryNo());
            }
            case BATTERY_IN -> {
                CellEntity cell = requireCell(cabinet, form);
                BatteryEntity battery = requireBattery(form);
                if (cell.getBatteryId() != null && !cell.getBatteryId().equals(battery.getId())) {
                    log.warn("台账不一致：还入电池覆盖了仓内登记 cabinetNo={} cellNo={} 原={} 新={}",
                            form.getCabinetNo(), form.getCellNo(), cell.getBatteryId(), battery.getId());
                }
                updateCell(cell.getId(), CellStatus.OCCUPIED, battery.getId());
                attachBattery(battery.getId(), cell.getId(), form.getSoc());
                // 分配集合：仓变占用（充电中，未满电不进 full）；订单域推进（SWAP/RETURN 完成）
                allocationService.onBatteryIn(cell.getId());
                orderEventService.onBatteryIn(cabinet, cell, battery, form.getCommandSeq());
                log.info("还电 cabinetNo={} cellNo={} batteryNo={} soc={}", form.getCabinetNo(), form.getCellNo(), form.getBatteryNo(), form.getSoc());
            }
            case SOC_REPORT -> {
                BatteryEntity battery = requireBattery(form);
                Integer soc = form.getSoc();
                BatteryStatus current = BatteryStatus.fromCode(battery.getStatus());
                if (current == BatteryStatus.LOANED || current == BatteryStatus.REPAIR
                        || current == BatteryStatus.RETIRED) {
                    // 借出/维修/退役电池不应因电量上报被改回在仓态：只更新电量，状态不动
                    log.warn("非在仓电池上报电量，仅更新 soc 不改状态 batteryNo={} status={} soc={}",
                            form.getBatteryNo(), current, soc);
                    updateSoc(battery.getId(), soc, null);
                } else {
                    Integer status = (soc != null && soc >= properties.getSocFullThreshold())
                            ? BatteryStatus.FULL.getCode() : BatteryStatus.CHARGING.getCode();
                    updateSoc(battery.getId(), soc, status);
                    if (status == BatteryStatus.FULL.getCode() && battery.getCellId() != null) {
                        // 满电即入可分配池
                        allocationService.onBatteryFull(battery.getCellId());
                    }
                    log.info("电量上报 batteryNo={} soc={} -> status={}", form.getBatteryNo(), soc, status);
                }
            }
            case CELL_FAULT -> {
                CellEntity cell = requireCell(cabinet, form);
                updateCell(cell.getId(), CellStatus.FAULT, cell.getBatteryId());
                allocationService.refreshByCellId(cell.getId());
                alarmService.raise(AlarmService.DEVICE_CELL, form.getCabinetNo() + "-" + form.getCellNo(),
                        AlarmType.CELL_FAULT, "仓位故障（电池 " + form.getBatteryNo() + "）");
                log.warn("仓位故障 cabinetNo={} cellNo={}", form.getCabinetNo(), form.getCellNo());
            }
            case DOOR_CLOSED -> log.debug("门关闭 cabinetNo={} cellNo={}（审计，不推进订单）",
                    form.getCabinetNo(), form.getCellNo());
            case CABINET_FAULT -> {
                // S3.2：柜故障 = 在途指令中断（显式 EXEC_FAILED）+ 活跃订单转人工
                int interrupted = commandLogService.markExecFailedByCabinet(form.getCabinetNo());
                orderEventService.onCabinetFault(cabinet);
                alarmService.raise(AlarmService.DEVICE_CABINET, form.getCabinetNo(),
                        AlarmType.CABINET_FAULT, "柜级故障（中断在途指令 " + interrupted + " 条）");
                log.warn("柜级故障 cabinetNo={} 中断在途指令={}", form.getCabinetNo(), interrupted);
            }
            default -> log.warn("事件语义未实现（忽略） type={}", type);
        }
    }

    private CellEntity requireCell(CabinetEntity cabinet, DeviceEventForm form) {
        if (form.getCellNo() == null) {
            throw new RRException("事件缺 cellNo: " + form.getEventType());
        }
        CellEntity cell = cellDao.selectOne(new LambdaQueryWrapper<CellEntity>()
                .eq(CellEntity::getCabinetId, cabinet.getId())
                .eq(CellEntity::getCellNo, form.getCellNo()));
        if (cell == null) {
            throw new RRException("仓不存在: " + form.getCabinetNo() + "#" + form.getCellNo());
        }
        return cell;
    }

    private BatteryEntity requireBattery(DeviceEventForm form) {
        if (form.getBatteryNo() == null || form.getBatteryNo().isEmpty()) {
            throw new RRException("事件缺 batteryNo: " + form.getEventType());
        }
        BatteryEntity battery = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                .eq(BatteryEntity::getBatteryNo, form.getBatteryNo()));
        if (battery == null) {
            throw new RRException("电池不存在: " + form.getBatteryNo());
        }
        return battery;
    }

    private void updateCell(Long cellId, CellStatus status, Long batteryId) {
        cellDao.update(null, new LambdaUpdateWrapper<CellEntity>()
                .eq(CellEntity::getId, cellId)
                .set(CellEntity::getStatus, status.getCode())
                .set(CellEntity::getBatteryId, batteryId)
                .set(CellEntity::getUpdateTime, System.currentTimeMillis()));
    }

    /** 借出：状态 LOANED、脱离仓位（cellId=null），holder 由订单域在 S2 绑定 */
    private void detachBattery(Long batteryId) {
        batteryDao.update(null, new LambdaUpdateWrapper<BatteryEntity>()
                .eq(BatteryEntity::getId, batteryId)
                .set(BatteryEntity::getStatus, BatteryStatus.LOANED.getCode())
                .set(BatteryEntity::getCellId, null)
                .set(BatteryEntity::getUpdateTime, System.currentTimeMillis()));
    }

    /** 归仓：状态 CHARGING、绑定仓位、清持有人（还电即归还） */
    private void attachBattery(Long batteryId, Long cellId, Integer soc) {
        LambdaUpdateWrapper<BatteryEntity> wrapper = new LambdaUpdateWrapper<BatteryEntity>()
                .eq(BatteryEntity::getId, batteryId)
                .set(BatteryEntity::getStatus, BatteryStatus.CHARGING.getCode())
                .set(BatteryEntity::getCellId, cellId)
                .set(BatteryEntity::getHolderUserId, null)
                .set(BatteryEntity::getUpdateTime, System.currentTimeMillis());
        if (soc != null) {
            wrapper.set(BatteryEntity::getSoc, soc);
        }
        batteryDao.update(null, wrapper);
    }

    /** 电量上报：更新电量（status 为 null 时不动状态——非在仓电池只记电量） */
    private void updateSoc(Long batteryId, Integer soc, Integer status) {
        LambdaUpdateWrapper<BatteryEntity> wrapper = new LambdaUpdateWrapper<BatteryEntity>()
                .eq(BatteryEntity::getId, batteryId)
                .set(BatteryEntity::getUpdateTime, System.currentTimeMillis());
        if (status != null) {
            wrapper.set(BatteryEntity::getStatus, status);
        }
        if (soc != null) {
            wrapper.set(BatteryEntity::getSoc, soc);
        }
        batteryDao.update(null, wrapper);
    }
}
