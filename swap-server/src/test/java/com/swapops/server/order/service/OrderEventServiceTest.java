package com.swapops.server.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.contract.OrderStatus;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 订单事件驱动单测：门开推进、TAKE 完成、SWAP 取/还、RETURN 完成、无 seq 忽略。
 */
@DisplayName("订单事件驱动（CAS 状态机）")
@ExtendWith(MockitoExtension.class)
class OrderEventServiceTest {

    @Mock
    private SwapOrderDao orderDao;
    @Mock
    private CellDao cellDao;
    @Mock
    private BatteryDao batteryDao;
    @Mock
    private BillingService billingService;
    @Mock
    private SwapOrderService swapOrderService;
    @Mock
    private com.swapops.server.order.service.delay.OrderDelayService orderDelayService;
    @InjectMocks
    private OrderEventService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SwapOrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, BatteryEntity.class);
    }

    private CabinetEntity cabinet() {
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(1L);
        cabinet.setCabinetNo("SWAP-C-001");
        return cabinet;
    }

    private CellEntity cell(long id, int cellNo) {
        CellEntity cell = new CellEntity();
        cell.setId(id);
        cell.setCellNo(cellNo);
        cell.setCabinetId(1L);
        return cell;
    }

    private BatteryEntity battery(long id, String no) {
        BatteryEntity battery = new BatteryEntity();
        battery.setId(id);
        battery.setBatteryNo(no);
        return battery;
    }

    private SwapOrderEntity order(long id, String no, String type, OrderStatus status, Long cellId, Long takeBatteryId) {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(id);
        order.setOrderNo(no);
        order.setOrderType(type);
        order.setStatus(status.getCode());
        order.setUserId(7L);
        order.setCellId(cellId);
        order.setTakeBatteryId(takeBatteryId);
        return order;
    }

    @Test
    @DisplayName("门开：目标仓一致 → CAS 推进 OPENED")
    void 门开推进() {
        when(orderDao.selectOne(any())).thenReturn(order(99L, "SWO-1", "TAKE", OrderStatus.PENDING_OPEN, 11L, null));
        when(cellDao.selectById(11L)).thenReturn(cell(11L, 3));
        when(orderDao.update(isNull(), any())).thenReturn(1);

        service.onDoorOpened(cabinet(), 3, 7L);

        verify(orderDao).update(isNull(), any());
        verify(orderDelayService).cancelAll(any(SwapOrderEntity.class));
        verify(orderDelayService).schedulePickup(any(SwapOrderEntity.class), anyLong());
    }

    @Test
    @DisplayName("门开：仓不符 → 不推进（防错仓事件污染订单）")
    void 门开仓不符不推进() {
        when(orderDao.selectOne(any())).thenReturn(order(99L, "SWO-1", "TAKE", OrderStatus.PENDING_OPEN, 11L, null));
        when(cellDao.selectById(11L)).thenReturn(cell(11L, 5));

        service.onDoorOpened(cabinet(), 3, 7L);

        verify(orderDao, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("TAKE 取电：完成订单 + 绑定持有人 + 计费")
    void take取电完成() {
        when(orderDao.selectOne(any())).thenReturn(order(99L, "SWO-1", "TAKE", OrderStatus.OPENED, 11L, null));
        when(orderDao.update(isNull(), any())).thenReturn(1);
        when(batteryDao.update(isNull(), any())).thenReturn(1);

        service.onBatteryOut(cabinet(), cell(11L, 3), battery(21L, "BAT-0001"), 7L);

        verify(billingService).charge(any(SwapOrderEntity.class), anyLong());
        verify(batteryDao).update(isNull(), any());
    }

    @Test
    @DisplayName("SWAP 取电：转 TAKEN 不计费（还电才结算）")
    void swap取电转待还() {
        when(orderDao.selectOne(any())).thenReturn(order(99L, "SWO-2", "SWAP", OrderStatus.OPENED, 11L, 21L));
        when(orderDao.update(isNull(), any())).thenReturn(1);

        service.onBatteryOut(cabinet(), cell(11L, 3), battery(21L, "BAT-0001"), 7L);

        verify(billingService, never()).charge(any(), anyLong());
        verify(orderDelayService).cancelAll(any(SwapOrderEntity.class));
        verify(orderDelayService).scheduleOverdue(any(SwapOrderEntity.class), anyLong());
    }

    @Test
    @DisplayName("SWAP 还电：完成订单 + 持有人转移 + 计费")
    void swap还电完成() {
        SwapOrderEntity swapOrder = order(99L, "SWO-2", "SWAP", OrderStatus.TAKEN, 11L, 21L);
        swapOrder.setReturnBatteryId(null);
        when(orderDao.selectOne(any())).thenReturn(swapOrder);
        when(batteryDao.selectOne(any())).thenReturn(battery(20L, "BAT-0009"));
        when(batteryDao.selectById(21L)).thenReturn(battery(21L, "BAT-0001"));
        when(orderDao.update(isNull(), any())).thenReturn(1);
        when(batteryDao.update(isNull(), any())).thenReturn(1);

        service.onBatteryIn(cabinet(), cell(11L, 3), battery(20L, "BAT-0009"), 7L);

        verify(billingService).charge(any(SwapOrderEntity.class), anyLong());
        verify(batteryDao).update(isNull(), any());
    }

    @Test
    @DisplayName("RETURN 还电：完成订单 + 退押金（计费），无取电前置")
    void return还电完成() {
        SwapOrderEntity returnOrder = order(99L, "SWO-3", "RETURN", OrderStatus.OPENED, 15L, null);
        when(orderDao.selectOne(any())).thenReturn(null, returnOrder);
        when(orderDao.update(isNull(), any())).thenReturn(1);

        service.onBatteryIn(cabinet(), cell(15L, 7), battery(20L, "BAT-0009"), 7L);

        verify(billingService).charge(any(SwapOrderEntity.class), anyLong());
    }

    @Test
    @DisplayName("柜故障（S3.2）：活跃订单全部转 EXCEPTION 交人工")
    void 柜故障活跃订单转异常() {
        SwapOrderEntity active = order(99L, "SWO-1", "TAKE", OrderStatus.PENDING_OPEN, 11L, null);
        when(orderDao.selectList(any())).thenReturn(java.util.List.of(active));

        service.onCabinetFault(cabinet());

        verify(swapOrderService).markException(any(SwapOrderEntity.class), eq("CABINET_FAULT"));
    }

    @Test
    @DisplayName("无 commandSeq（非订单驱动事件）：订单域零副作用")
    void 无seq忽略() {
        service.onDoorOpened(cabinet(), 3, null);
        service.onBatteryOut(cabinet(), cell(11L, 3), battery(21L, "BAT-0001"), null);
        service.onBatteryIn(cabinet(), cell(11L, 3), battery(21L, "BAT-0001"), null);

        verifyNoInteractions(orderDao, cellDao, batteryDao, billingService);
    }
}
