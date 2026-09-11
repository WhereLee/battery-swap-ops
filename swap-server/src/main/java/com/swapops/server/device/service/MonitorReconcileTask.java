package com.swapops.server.device.service;

import com.swapops.contract.CommandAction;
import com.swapops.contract.OrderStatus;
import com.swapops.server.common.RRException;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.SwapOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 指令超时对账（S3.2，协议 §2.4 QUERY_STATE + T20）：
 * 对超时在途指令先向设备拿实况，再按证据仲裁——
 * <ol>
 *   <li>查询失败（下行不可达）→ 重试分层；</li>
 *   <li>设备已执行（lastCommandSeq≥seq，事件丢失）→ 指令按证据销账；订单按仓快照推进
 *       （门已开→OPENED；电池已取走但事件链缺失→EXCEPTION 交人工）；</li>
 *   <li>设备未执行 → 同 seq 重试（设备幂等）；达上限 → RETRY_EXCEEDED 转人工。</li>
 * </ol>
 * 单条异常不中断整轮（下轮自愈）；S3.3 引入任务租约锁后本任务纳入多实例互斥。
 */
@Slf4j
@Component
public class MonitorReconcileTask {

    private final DeviceChannelProperties properties;
    private final CommandLogService commandLogService;
    private final CommandDispatchService commandDispatchService;
    private final SwapOrderService swapOrderService;
    private final CellDao cellDao;

    public MonitorReconcileTask(DeviceChannelProperties properties, CommandLogService commandLogService,
                                CommandDispatchService commandDispatchService,
                                SwapOrderService swapOrderService, CellDao cellDao) {
        this.properties = properties;
        this.commandLogService = commandLogService;
        this.commandDispatchService = commandDispatchService;
        this.swapOrderService = swapOrderService;
        this.cellDao = cellDao;
    }

    @Scheduled(fixedDelayString = "${swap.device.reconcile-interval-ms:15000}")
    public void reconcile() {
        List<CommandLogEntity> timeouts = commandLogService.findTimeoutPending(
                properties.getCommandTimeoutSeconds(), properties.getReconcileBatch());
        for (CommandLogEntity cmd : timeouts) {
            try {
                reconcileOne(cmd);
            } catch (Exception e) {
                // 单条异常（查询超时/DB 抖动）不中断整轮：记日志后继续，下轮自愈
                log.warn("对账单条异常跳过 cabinetNo={} seq={} cause={}",
                        cmd.getCabinetNo(), cmd.getCommandSeq(), e.getMessage());
            }
        }
    }

    private void reconcileOne(CommandLogEntity cmd) {
        String cabinetNo = cmd.getCabinetNo();
        CommandDispatchService.QueryResult qr = commandDispatchService.queryState(cabinetNo);
        if (qr == null || qr.lastCommandSeq() == null || qr.lastCommandSeq() < cmd.getCommandSeq()) {
            retryOrExceed(cmd);
            return;
        }
        // 设备已执行：指令按证据销账（事件丢失场景），订单按快照推进
        boolean arrived = commandLogService.markArrivedBySeq(cabinetNo, cmd.getCommandSeq(),
                CommandAction.OPEN_CELL);
        SwapOrderEntity order = swapOrderService.findByOpenCommand(cabinetNo, cmd.getCommandSeq());
        if (order != null && order.getStatus() == OrderStatus.PENDING_OPEN.getCode()) {
            CellEntity cell = order.getCellId() == null ? null : cellDao.selectById(order.getCellId());
            CommandDispatchService.CellSnapshot snap = cell == null ? null : qr.cells().get(cell.getCellNo());
            if (snap != null && !snap.hasBattery()) {
                // 电池已取出但事件链缺失：不能安全自动补记（数量/时序无证据）——转人工
                swapOrderService.markException(order, "EVIDENCE_LOST_TAKEN");
                log.error("对账：指令已执行且电池已取出但事件缺失，订单转人工 orderNo={} seq={}",
                        order.getOrderNo(), cmd.getCommandSeq());
            } else {
                swapOrderService.markOpenedByEvidence(order);
            }
        }
        log.info("对账：指令已按设备证据销账 cabinetNo={} seq={} arrived={}",
                cabinetNo, cmd.getCommandSeq(), arrived);
    }

    private void retryOrExceed(CommandLogEntity cmd) {
        int maxRetry = properties.getMaxRetry();
        if (cmd.getRetryCount() != null && cmd.getRetryCount() >= maxRetry) {
            if (commandLogService.markRetryExceeded(cmd.getId())) {
                log.error("指令重试超限停止重试（转人工；告警 RETRY_EXCEEDED 由 S3.6 接管）"
                                + " cabinetNo={} seq={} retry={}",
                        cmd.getCabinetNo(), cmd.getCommandSeq(), cmd.getRetryCount());
            }
            return;
        }
        SwapOrderEntity order = swapOrderService.findByOpenCommand(cmd.getCabinetNo(), cmd.getCommandSeq());
        CellEntity cell = order == null || order.getCellId() == null ? null : cellDao.selectById(order.getCellId());
        if (cell == null) {
            log.debug("对账重试跳过（无订单绑定仓，如联调指令） cabinetNo={} seq={}",
                    cmd.getCabinetNo(), cmd.getCommandSeq());
            return;
        }
        log.info("指令超时重试 cabinetNo={} seq={} retry={}/{}",
                cmd.getCabinetNo(), cmd.getCommandSeq(), cmd.getRetryCount(), maxRetry);
        try {
            commandDispatchService.dispatchPrepared(cmd, cmd.getCabinetNo(), cell.getCellNo(), false);
        } catch (RRException e) {
            log.warn("对账重试下发失败 cabinetNo={} seq={} cause={}",
                    cmd.getCabinetNo(), cmd.getCommandSeq(), e.getMessage());
        } finally {
            // 重试计数（无论链路成败：防无限重试）；设备拒绝场景由 S3.2 分层收敛为 SEND_FAILED
            commandLogService.markRetried(cmd.getId());
        }
    }
}
