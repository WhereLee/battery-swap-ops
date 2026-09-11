package com.swapops.server.device.service;

import com.swapops.contract.OrderStatus;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.SwapOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 超时对账单测（S3.2）：查询失败重试分层、设备已执行按证据销账、事件链缺失转人工、无绑定跳过。
 */
@DisplayName("指令超时对账（S3.2）")
@ExtendWith(MockitoExtension.class)
class MonitorReconcileTaskTest {

    @Mock
    private DeviceChannelProperties properties;
    @Mock
    private CommandLogService commandLogService;
    @Mock
    private CommandDispatchService commandDispatchService;
    @Mock
    private SwapOrderService swapOrderService;
    @Mock
    private CellDao cellDao;
    @Mock
    private JobLockService jobLockService;
    @Mock
    private com.swapops.server.alarm.service.AlarmService alarmService;
    @Mock
    private com.swapops.server.alarm.service.TaskWatchdog watchdog;
    @InjectMocks
    private MonitorReconcileTask task;

    @BeforeEach
    void runLockInline() {
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return true;
        });
    }

    private CommandLogEntity cmd(int retryCount) {
        CommandLogEntity cmd = new CommandLogEntity();
        cmd.setId(100L);
        cmd.setCabinetNo("SWAP-C-001");
        cmd.setCommandSeq(7L);
        cmd.setRetryCount(retryCount);
        return cmd;
    }

    private SwapOrderEntity order(OrderStatus status, Long cellId) {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setOrderNo("SWO-1");
        order.setStatus(status.getCode());
        order.setCellId(cellId);
        return order;
    }

    private CellEntity cell() {
        CellEntity cell = new CellEntity();
        cell.setId(11L);
        cell.setCellNo(3);
        return cell;
    }

    private void stubSweep(CommandLogEntity cmd) {
        when(commandLogService.findTimeoutPending(anyInt(), anyInt())).thenReturn(List.of(cmd));
        when(properties.getCommandTimeoutSeconds()).thenReturn(60);
        when(properties.getReconcileBatch()).thenReturn(100);
    }

    @Test
    @DisplayName("查询失败：同 seq 重试（不置终态，计数+1）")
    void 查询失败重试() {
        CommandLogEntity cmd = cmd(1);
        stubSweep(cmd);
        when(properties.getMaxRetry()).thenReturn(3);
        when(commandDispatchService.queryState("SWAP-C-001")).thenReturn(null);
        when(swapOrderService.findByOpenCommand("SWAP-C-001", 7L)).thenReturn(order(OrderStatus.PENDING_OPEN, 11L));
        when(cellDao.selectById(11L)).thenReturn(cell());

        task.reconcile();

        verify(commandDispatchService).dispatchPrepared(cmd, "SWAP-C-001", 3, false);
        verify(commandLogService).markRetried(100L);
    }

    @Test
    @DisplayName("重试超限：定格 RETRY_EXCEEDED，不再下发")
    void 重试超限() {
        CommandLogEntity cmd = cmd(3);
        stubSweep(cmd);
        when(properties.getMaxRetry()).thenReturn(3);
        when(commandDispatchService.queryState("SWAP-C-001")).thenReturn(null);
        when(commandLogService.markRetryExceeded(100L)).thenReturn(true);

        task.reconcile();

        verify(commandLogService).markRetryExceeded(100L);
        verify(commandDispatchService, never()).dispatchPrepared(any(), any(), anyInt(), eq(false));
    }

    @Test
    @DisplayName("设备已执行（事件丢失）：指令销账 + 订单按门开证据推进 OPENED")
    void 设备已执行门开证据() {
        CommandLogEntity cmd = cmd(1);
        stubSweep(cmd);
        when(commandDispatchService.queryState("SWAP-C-001")).thenReturn(
                new CommandDispatchService.QueryResult(1, Map.of(3,
                        new CommandDispatchService.CellSnapshot(3, true, "BAT-0001", 100)),
                        "boot-1", 42L, 7L));
        when(commandLogService.markArrivedBySeq("SWAP-C-001", 7L,
                com.swapops.contract.CommandAction.OPEN_CELL)).thenReturn(true);
        SwapOrderEntity order = order(OrderStatus.PENDING_OPEN, 11L);
        when(swapOrderService.findByOpenCommand("SWAP-C-001", 7L)).thenReturn(order);
        when(cellDao.selectById(11L)).thenReturn(cell());

        task.reconcile();

        verify(commandLogService).markArrivedBySeq(eq("SWAP-C-001"), eq(7L), any());
        verify(swapOrderService).markOpenedByEvidence(order);
    }

    @Test
    @DisplayName("设备已执行且电池已取走但事件链缺失：订单转人工异常（不自动补记）")
    void 设备已执行电池已取走转人工() {
        CommandLogEntity cmd = cmd(1);
        stubSweep(cmd);
        when(commandDispatchService.queryState("SWAP-C-001")).thenReturn(
                new CommandDispatchService.QueryResult(2, Map.of(3,
                        new CommandDispatchService.CellSnapshot(3, false, null, null)),
                        "boot-1", 43L, 7L));
        SwapOrderEntity order = order(OrderStatus.PENDING_OPEN, 11L);
        when(swapOrderService.findByOpenCommand("SWAP-C-001", 7L)).thenReturn(order);
        when(cellDao.selectById(11L)).thenReturn(cell());

        task.reconcile();

        verify(swapOrderService).markException(order, "EVIDENCE_LOST_TAKEN");
        verify(swapOrderService, never()).markOpenedByEvidence(any());
    }

    @Test
    @DisplayName("无订单绑定的在途指令（联调）：跳过重试，不误伤")
    void 无订单绑定跳过() {
        CommandLogEntity cmd = cmd(0);
        stubSweep(cmd);
        when(properties.getMaxRetry()).thenReturn(3);
        when(commandDispatchService.queryState("SWAP-C-001")).thenReturn(null);
        when(swapOrderService.findByOpenCommand("SWAP-C-001", 7L)).thenReturn(null);

        task.reconcile();

        verifyNoInteractions(cellDao);
        verify(commandDispatchService, never()).dispatchPrepared(any(), any(), anyInt(), eq(false));
        verify(commandLogService, never()).markRetried(anyLong());
    }
}
