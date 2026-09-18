package com.swapops.server.web.view.service;

import com.swapops.contract.OrderStatus;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import com.swapops.server.alarm.dao.AlarmDao;
import com.swapops.server.alarm.entity.AlarmEntity;
import com.swapops.server.common.RRException;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.dao.CommandLogDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import com.swapops.server.order.dao.PaymentRecordDao;
import com.swapops.server.order.dao.RefundRecordDao;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.PaymentRecordEntity;
import com.swapops.server.order.entity.RefundRecordEntity;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.pay.RefundService;
import com.swapops.server.web.view.AdminViews;
import com.swapops.server.workorder.entity.WorkOrderEntity;
import com.swapops.server.workorder.entity.WorkOrderLogEntity;
import com.swapops.server.workorder.service.WorkOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 流程口视图测试：工单/柜/订单聚合映射 + 越权 fail-closed + 敏感字段不外泄。 */
@DisplayName("流程口视图（工单/柜详情/订单详情）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminWorkflowViewServiceTest {

    @Mock
    private WorkOrderService workOrderService;
    @Mock
    private AlarmDao alarmDao;
    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private CellDao cellDao;
    @Mock
    private BatteryDao batteryDao;
    @Mock
    private CommandLogDao commandLogDao;
    @Mock
    private SwapOrderDao swapOrderDao;
    @Mock
    private PaymentRecordDao paymentRecordDao;
    @Mock
    private RefundRecordDao refundRecordDao;
    @Mock
    private RefundService refundService;

    private AdminWorkflowViewService service;

    @BeforeEach
    void setUp() {
        service = new AdminWorkflowViewService(workOrderService, alarmDao, cabinetDao, cellDao, batteryDao,
                commandLogDao, swapOrderDao, paymentRecordDao, refundRecordDao, refundService);
    }

    @AfterEach
    void tearDown() {
        AdminContext.set(null);
    }

    private WorkOrderEntity workOrder(int status) {
        WorkOrderEntity order = new WorkOrderEntity();
        order.setId(1L);
        order.setWoNo("WO1");
        order.setAlarmId(9L);
        order.setSource("ALARM");
        order.setDeviceNo("SWAP-C-005");
        order.setStationId(7L);
        order.setSeverity("HIGH");
        order.setStatus(status);
        order.setCreateTime(1700000000000L);
        return order;
    }

    private CabinetEntity cabinet(long stationId) {
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(11L);
        cabinet.setCabinetNo("SWAP-C-005");
        cabinet.setStationId(stationId);
        cabinet.setCellCount(2);
        cabinet.setStatus(1);
        cabinet.setSecret("0123456789abcdef0123456789abcdef"); // 密钥绝不应出现在视图里
        cabinet.setLastHeartbeatTime(1699999990000L);
        return cabinet;
    }

    @Test
    @DisplayName("工单页：字段映射 + OPEN 态给 triage（能力位来自服务端）")
    void 工单页映射与能力位() {
        when(workOrderService.page(1, 20, null))
                .thenReturn(PageResult.of(List.of(workOrder(1)), 1, 1, 20));

        AdminViews.WorkOrderVO vo = service.workOrderPage(1, 20, null).getList().get(0);

        assertThat(vo.woNo()).isEqualTo("WO1");
        assertThat(vo.stationId()).isEqualTo(7L);
        assertThat(vo.allowedActions()).containsExactly("triage");
    }

    @Test
    @DisplayName("工单详情：带流转日志与来源告警摘要（前端不必再查告警接口）")
    void 工单详情聚合() {
        when(workOrderService.require(1L)).thenReturn(workOrder(3));
        WorkOrderLogEntity logEntity = new WorkOrderLogEntity();
        logEntity.setWoNo("WO1");
        logEntity.setAction("ASSIGN");
        logEntity.setFromStatus(2);
        logEntity.setToStatus(3);
        logEntity.setOperator("ops01");
        logEntity.setCreateTime(1700000001000L);
        when(workOrderService.logs("WO1")).thenReturn(List.of(logEntity));
        AlarmEntity alarm = new AlarmEntity();
        alarm.setId(9L);
        alarm.setAlarmType("CABINET_FAULT");
        alarm.setDeviceNo("SWAP-C-005");
        alarm.setContent("柜级故障");
        when(alarmDao.selectById(9L)).thenReturn(alarm);

        AdminViews.WorkOrderDetailVO detail = service.workOrderDetail(1L);

        assertThat(detail.logs()).hasSize(1);
        assertThat(detail.logs().get(0).action()).isEqualTo("ASSIGN");
        assertThat(detail.order().allowedActions()).containsExactly("start"); // ASSIGNED → start
        assertThat(detail.alarm().alarmType()).isEqualTo("CABINET_FAULT");
    }

    @Test
    @DisplayName("柜详情：仓带电池号与 SOC、心跳年龄服务端算好、三类嵌套列表齐")
    void 柜详情聚合() {
        when(cabinetDao.selectOne(any())).thenReturn(cabinet(7L));
        CellEntity cell = new CellEntity();
        cell.setId(88L);
        cell.setCellNo(1);
        cell.setStatus(2);
        cell.setBatteryId(55L);
        cell.setLockOrderId(66L);
        when(cellDao.selectList(any())).thenReturn(List.of(cell));
        BatteryEntity battery = new BatteryEntity();
        battery.setId(55L);
        battery.setBatteryNo("BAT-0049");
        battery.setSoc(88);
        when(batteryDao.selectBatchIds(any())).thenReturn(List.of(battery));
        AlarmEntity alarm = new AlarmEntity();
        alarm.setId(3L);
        alarm.setAlarmType("CELL_FAULT");
        alarm.setDeviceNo("SWAP-C-005-1");
        when(alarmDao.selectList(any())).thenReturn(List.of(alarm));
        SwapOrderEntity order = new SwapOrderEntity();
        order.setOrderNo("SWO-1");
        order.setOrderType("SWAP");
        order.setStatus(OrderStatus.OPENED.getCode());
        order.setFeeFen(300);
        when(swapOrderDao.selectList(any())).thenReturn(List.of(order));
        CommandLogEntity command = new CommandLogEntity();
        command.setCommandAction("OPEN_CELL");
        command.setCommandSeq(12L);
        command.setCommandStatus(2);
        when(commandLogDao.selectList(any())).thenReturn(List.of(command));

        AdminViews.CabinetDetailVO detail = service.cabinetDetail("SWAP-C-005");

        assertThat(detail.cells()).hasSize(1);
        assertThat(detail.cells().get(0).batteryNo()).isEqualTo("BAT-0049");
        assertThat(detail.cells().get(0).soc()).isEqualTo(88);
        assertThat(detail.cabinet().heartbeatAgeMs()).isNotNull().isPositive();
        assertThat(detail.openAlarms()).extracting(AdminViews.AlarmBriefVO::alarmType).containsExactly("CELL_FAULT");
        assertThat(detail.activeOrders()).extracting(AdminViews.OrderBriefVO::orderNo).containsExactly("SWO-1");
        assertThat(detail.recentCommands()).extracting(AdminViews.CommandVO::commandAction)
                .containsExactly("OPEN_CELL");
    }

    @Test
    @DisplayName("柜档案视图不含密钥字段（Entity 直出会泄漏 secret，这是本层存在的理由之一）")
    void 柜视图不含密钥() {
        List<String> components = Arrays.stream(AdminViews.CabinetVO.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(components).doesNotContain("secret", "lastBootId_secret");
        assertThat(components).contains("cabinetNo", "status", "lastHeartbeatTime");
    }

    @Test
    @DisplayName("柜详情：受限身份访问越域柜 → 403（聚合接口不得成为数据权限旁路）")
    void 柜详情越域拒绝() {
        AdminContext.set(new AdminContext.Principal(9L, "ops01", AdminRole.OPS, false, "STATION", Set.of(3L)));
        when(cabinetDao.selectOne(any())).thenReturn(cabinet(7L));

        assertThatThrownBy(() -> service.cabinetDetail("SWAP-C-005"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无权访问该站点数据");
    }

    @Test
    @DisplayName("柜不存在：400 业务错（不静默返回空视图）")
    void 柜不存在() {
        when(cabinetDao.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.cabinetDetail("SWAP-C-999"))
                .isInstanceOf(RRException.class).hasMessageContaining("柜不存在");
    }

    @Test
    @DisplayName("订单详情：可退金额取服务端资金口径，支付/退款流水与状态描述齐")
    void 订单详情聚合() {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(100L);
        order.setOrderNo("SWO-1");
        order.setOrderType("SWAP");
        order.setStatus(OrderStatus.COMPLETED.getCode());
        order.setStationId(7L);
        order.setFeeFen(300);
        order.setDiscountFen(100);
        order.setPayType("BALANCE");
        order.setCreateTime(1700000000000L);
        when(swapOrderDao.selectOne(any())).thenReturn(order);
        PaymentRecordEntity payment = new PaymentRecordEntity();
        payment.setTradeNo("T1");
        payment.setAmountFen(300);
        payment.setPaymentType("BALANCE_FEE");
        payment.setStatus(1);
        when(paymentRecordDao.selectList(any())).thenReturn(List.of(payment));
        RefundRecordEntity refund = new RefundRecordEntity();
        refund.setRefundNo("RF1");
        refund.setAmountFen(300);
        refund.setReason("ADMIN_REVERSAL");
        refund.setStatus("SUCCESS");
        when(refundRecordDao.selectList(any())).thenReturn(List.of(refund));
        when(refundService.refundableAmount(100L)).thenReturn(300);

        AdminViews.OrderDetailVO detail = service.orderDetail("SWO-1");

        assertThat(detail.statusDesc()).isEqualTo("COMPLETED");
        assertThat(detail.refundableFen()).isEqualTo(300); // 不由前端按流水推算
        assertThat(detail.payments()).hasSize(1);
        assertThat(detail.refunds()).extracting(AdminViews.RefundVO::reason).containsExactly("ADMIN_REVERSAL");
    }

    @Test
    @DisplayName("订单详情：受限身份越域订单 → 403")
    void 订单详情越域拒绝() {
        AdminContext.set(new AdminContext.Principal(9L, "ops01", AdminRole.OPS, false, "STATION", Set.of(3L)));
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(100L);
        order.setOrderNo("SWO-1");
        order.setStationId(99L);
        when(swapOrderDao.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> service.orderDetail("SWO-1"))
                .isInstanceOf(RRException.class).hasMessageContaining("无权访问该站点数据");
    }

    @Test
    @DisplayName("订单不存在：400 业务错")
    void 订单不存在() {
        when(swapOrderDao.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.orderDetail("SWO-404"))
                .isInstanceOf(RRException.class).hasMessageContaining("订单不存在");
    }
}
