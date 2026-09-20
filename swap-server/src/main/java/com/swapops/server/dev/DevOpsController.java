package com.swapops.server.dev;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.server.common.Result;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import com.swapops.server.device.service.CommandDispatchService;
import com.swapops.server.device.service.CommandLogService;
import com.swapops.server.device.service.MonitorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联调端点（仅 swap.dev.enabled=true）：触发开仓指令 / 查询指令与柜状态。
 * 供剧本 1 证据脚本使用；生产形态由订单域（S2）驱动下发。
 *
 * <p><b>批次43 补（独立审计 F-07）</b>：这些端点此前<b>只有 dev 开关</b>这一道门——它们既不在
 * {@code AdminSecurityConfig} 的 securityMatcher（{@code /admin/**}、{@code /actuator/**}）内，
 * 也不在 {@code WebConfig} 的 {@code /user/**} 拦截器内，因此 {@code POST /api/dev/device/open}
 * 在 dev 开关打开时是<b>匿名可开任意柜门</b>，{@code /reset} 更会删欠费/券/站内信/结算单并重置钱包。
 * 现在四个端点统一走 {@link DevLoopbackGuard}（本机调用闸门）。dev 开关默认 false、systemd 不带该 flag，
 * 所以稳态本就不可达；这里收的是"误开/播种窗口"那一小段时间。
 */
@RestController
@RequestMapping("dev/device")
@ConditionalOnProperty(prefix = "swap.dev", name = "enabled", havingValue = "true")
public class DevOpsController {

    private final CommandDispatchService commandDispatchService;
    private final CommandLogService commandLogService;
    private final MonitorService monitorService;
    private final CabinetDao cabinetDao;
    private final CellDao cellDao;
    private final BatteryDao batteryDao;
    private final DevResetService devResetService;
    private final DevLoopbackGuard loopbackGuard;

    public DevOpsController(CommandDispatchService commandDispatchService, CommandLogService commandLogService,
                            MonitorService monitorService, CabinetDao cabinetDao, CellDao cellDao,
                            BatteryDao batteryDao, DevResetService devResetService,
                            DevLoopbackGuard loopbackGuard) {
        this.commandDispatchService = commandDispatchService;
        this.commandLogService = commandLogService;
        this.monitorService = monitorService;
        this.cabinetDao = cabinetDao;
        this.cellDao = cellDao;
        this.batteryDao = batteryDao;
        this.devResetService = devResetService;
        this.loopbackGuard = loopbackGuard;
    }

    /** 联调数据重置：活跃订单取消 + 电池归位 + 分配池重建（幂等；剧本 _c0 前置） */
    @PostMapping("/reset")
    public Result<Map<String, Object>> reset(HttpServletRequest request) {
        loopbackGuard.require(request, "dev/device/reset");
        return Result.ok(devResetService.reset());
    }

    @PostMapping("/open")
    public Result<Map<String, Object>> open(@RequestParam String cabinetNo, @RequestParam int cellNo,
                                            HttpServletRequest request) {
        loopbackGuard.require(request, "dev/device/open");
        CommandLogEntity log = commandDispatchService.openCell(cabinetNo, cellNo);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commandId", log.getId());
        data.put("commandSeq", log.getCommandSeq());
        data.put("traceId", log.getTraceId());
        return Result.ok(data);
    }

    @GetMapping("/command")
    public Result<Map<String, Object>> command(@RequestParam String cabinetNo, @RequestParam long seq,
                                               HttpServletRequest request) {
        loopbackGuard.require(request, "dev/device/command");
        CommandLogEntity log = commandLogService.getByCabinetSeq(cabinetNo, seq);
        if (log == null) {
            return Result.error(1, "指令不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commandId", log.getId());
        data.put("commandSeq", log.getCommandSeq());
        data.put("commandStatus", log.getCommandStatus());
        data.put("retryCount", log.getRetryCount());
        data.put("traceId", log.getTraceId());
        return Result.ok(data);
    }

    @GetMapping("/cabinet")
    public Result<Map<String, Object>> cabinet(@RequestParam String cabinetNo, HttpServletRequest request) {
        loopbackGuard.require(request, "dev/device/cabinet");
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo));
        if (cabinet == null) {
            return Result.error(1, "柜不存在");
        }
        List<CellEntity> cells = cellDao.selectList(new LambdaQueryWrapper<CellEntity>()
                .eq(CellEntity::getCabinetId, cabinet.getId()).orderByAsc(CellEntity::getCellNo));
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
        data.put("status", cabinet.getStatus());
        data.put("online", monitorService.isOnline(cabinetNo));
        data.put("lastBootId", cabinet.getLastBootId());
        data.put("lastEventSeq", cabinet.getLastEventSeq());
        data.put("cells", cellViews);
        return Result.ok(data);
    }
}
