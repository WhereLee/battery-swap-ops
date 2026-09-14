package com.swapops.server.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.contract.OrderStatus;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.alarm.service.TaskWatchdog;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.config.BillingProperties;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单超时扫描单测（S2 兜底 + S5 审查补 OVERDUE 超长未处置转人工）。
 */
@DisplayName("订单超时扫描（兜底补偿）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderSweepTaskTest {

    @Mock
    private SwapOrderDao orderDao;
    @Mock
    private SwapOrderService swapOrderService;
    @Mock
    private JobLockService jobLockService;
    @Mock
    private TaskWatchdog watchdog;
    @Mock
    private AlarmService alarmService;

    private OrderSweepTask task;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SwapOrderEntity.class);
    }

    @BeforeEach
    void setUp() {
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });
        task = new OrderSweepTask(orderDao, swapOrderService, new BillingProperties(),
                jobLockService, watchdog, alarmService);
    }

    private SwapOrderEntity order(OrderStatus status, long time) {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setOrderNo("SWO-1");
        order.setStatus(status.getCode());
        order.setPreemptExpireTime(time);
        order.setOpenTime(time);
        order.setTakeTime(time);
        return order;
    }

    @Test
    @DisplayName("预占/取电超时：PENDING_OPEN 与 OPENED 到点关闭")
    void 预占取电超时关闭() {
        long now = System.currentTimeMillis();
        when(orderDao.selectList(any())).thenReturn(
                List.of(order(OrderStatus.PENDING_OPEN, now - 1)),
                List.of(order(OrderStatus.OPENED, now - 1)),
                List.of());

        task.sweep();

        verify(swapOrderService).closeTimedOut(any(SwapOrderEntity.class), eq("PREEMPT_TIMEOUT"));
        verify(swapOrderService).closeTimedOut(any(SwapOrderEntity.class), eq("PICKUP_TIMEOUT"));
        verify(swapOrderService, never()).markException(any(), anyString());
    }

    @Test
    @DisplayName("OVERDUE 超长未处置（> overdueMaxHours）：转 EXCEPTION + 告警（S5 审查修复）")
    void 超长超期转人工() {
        long take = System.currentTimeMillis() - 200L * 3600 * 1000;
        when(orderDao.selectList(any())).thenReturn(
                List.of(), List.of(), List.of(order(OrderStatus.OVERDUE, take)));
        when(swapOrderService.markException(any(SwapOrderEntity.class), eq("OVERDUE_UNRESOLVED")))
                .thenReturn(true);

        task.sweep();

        verify(swapOrderService).markException(any(SwapOrderEntity.class), eq("OVERDUE_UNRESOLVED"));
        verify(alarmService).raise(eq(AlarmService.DEVICE_ORDER), eq("SWO-1"),
                eq(AlarmType.ORDER_OVERDUE), anyString());
    }

    @Test
    @DisplayName("OVERDUE 转人工 CAS 未命中（已被归还事件推进）：不重复告警")
    void 转人工未命中不告警() {
        long take = System.currentTimeMillis() - 200L * 3600 * 1000;
        when(orderDao.selectList(any())).thenReturn(
                List.of(), List.of(), List.of(order(OrderStatus.OVERDUE, take)));
        when(swapOrderService.markException(any(SwapOrderEntity.class), eq("OVERDUE_UNRESOLVED")))
                .thenReturn(false);

        task.sweep();

        verify(alarmService, never()).raise(anyString(), anyString(), any(AlarmType.class), anyString());
    }
}
