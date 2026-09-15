package com.swapops.server.reconcile;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.contract.OrderStatus;
import com.swapops.server.config.BillingProperties;
import com.swapops.server.device.config.DeviceChannelProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.dao.CommandLogDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import com.swapops.server.order.dao.PaymentRecordDao;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 日终对账单测（S3.5）：五组不变量检出/零差异。
 */
@DisplayName("日终对账（五组不变量）")
@ExtendWith(MockitoExtension.class)
class ReconcileServiceTest {

    @Mock
    private SwapOrderDao orderDao;
    @Mock
    private BatteryDao batteryDao;
    @Mock
    private CellDao cellDao;
    @Mock
    private PaymentRecordDao paymentRecordDao;
    @Mock
    private CommandLogDao commandLogDao;
    @Mock
    private com.swapops.server.alarm.service.AlarmService alarmService;
    @Mock
    private com.swapops.server.device.service.BatteryCycleService batteryCycleService;
    @Mock
    private com.swapops.server.transfer.dao.TransferTaskDao transferTaskDao;
    @Mock
    private com.swapops.server.transfer.dao.TransferTaskItemDao transferTaskItemDao;
    @Mock
    private com.swapops.server.agent.dao.AgentActionDao agentActionDao;

    @Mock
    private com.swapops.server.order.dao.ArrearsRecordDao arrearsRecordDao;
    @Mock
    private com.swapops.server.user.dao.UserCouponDao userCouponDao;

    private ReconcileService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SwapOrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, BatteryEntity.class);
        TableInfoHelper.initTableInfo(assistant, CellEntity.class);
        TableInfoHelper.initTableInfo(assistant, CommandLogEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.order.entity.ArrearsRecordEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.user.entity.UserCouponEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new ReconcileService(orderDao, batteryDao, cellDao, paymentRecordDao, commandLogDao,
                new BillingProperties(), new DeviceChannelProperties(), alarmService, batteryCycleService,
                transferTaskDao, transferTaskItemDao, agentActionDao,
                arrearsRecordDao, userCouponDao, 3600, 24, 200);
    }

    @Test
    @DisplayName("零差异：五组检查全过，报告 total=0")
    void 零差异() {
        when(orderDao.selectList(any())).thenReturn(List.of());
        when(batteryDao.selectList(any())).thenReturn(List.of());
        when(cellDao.selectList(any())).thenReturn(List.of());
        when(batteryDao.selectMaps(any())).thenReturn(List.of());
        when(commandLogDao.selectList(any())).thenReturn(List.of());

        ReconcileService.ReconcileReport report = service.run();

        assertThat(report.checks()).hasSize(11);
        assertThat(report.totalViolations()).isZero();
    }

    @Test
    @DisplayName("① 卡滞活跃单：PENDING_OPEN/OPENED/TAKEN 超龄均检出")
    void 卡滞活跃单检出() {
        SwapOrderEntity pending = new SwapOrderEntity();
        pending.setOrderNo("SWO-1");
        SwapOrderEntity opened = new SwapOrderEntity();
        opened.setOrderNo("SWO-2");
        SwapOrderEntity taken = new SwapOrderEntity();
        taken.setOrderNo("SWO-3");
        when(orderDao.selectList(any())).thenReturn(List.of(pending), List.of(opened), List.of(taken), List.of());

        ReconcileService.CheckResult result = service.checkStaleActive();

        assertThat(result.violations()).isEqualTo(3);
        assertThat(result.samples()).hasSize(3);
    }

    @Test
    @DisplayName("② 电池-仓双向不一致：两个方向均检出")
    void 电池仓不一致检出() {
        CellEntity cell = new CellEntity();
        cell.setId(11L);
        cell.setBatteryId(21L);
        BatteryEntity battery = new BatteryEntity();
        battery.setId(21L);
        battery.setCellId(999L);
        when(cellDao.selectList(any())).thenReturn(List.of(cell));
        when(batteryDao.selectById(21L)).thenReturn(battery);

        ReconcileService.CheckResult result = service.checkCellBatteryConsistency();

        assertThat(result.violations()).isEqualTo(1);
    }

    @Test
    @DisplayName("③ 完成单缺支付流水：检出；有流水则过")
    void 完成单缺流水检出() {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setOrderNo("SWO-1");
        order.setOrderType("SWAP");
        when(orderDao.selectList(any())).thenReturn(List.of(order));
        when(paymentRecordDao.selectCount(any())).thenReturn(0L);

        assertThat(service.checkCompletedHasPayment().violations()).isEqualTo(1);

        when(paymentRecordDao.selectCount(any())).thenReturn(1L);
        assertThat(service.checkCompletedHasPayment().violations()).isZero();
    }

    @Test
    @DisplayName("④ 持有人一致性：非 LOANED 检出；一人多电检出")
    void 持有人不一致检出() {
        BatteryEntity held = new BatteryEntity();
        held.setBatteryNo("BAT-1");
        held.setStatus(com.swapops.contract.BatteryStatus.FULL.getCode());
        held.setHolderUserId(7L);
        when(batteryDao.selectList(any())).thenReturn(List.of(held));
        when(batteryDao.selectMaps(any())).thenReturn(List.of(Map.of("holderUserId", 7L, "cnt", 2L)));

        assertThat(service.checkHolderConsistency().violations()).isEqualTo(2);
    }

    @Test
    @DisplayName("⑥ 逃逸电池（LOANED 且无仓无持有人）：检出")
    void 逃逸电池检出() {
        BatteryEntity escaped = new BatteryEntity();
        escaped.setBatteryNo("BAT-ESCAPED");
        escaped.setStatus(com.swapops.contract.BatteryStatus.LOANED.getCode());
        escaped.setCellId(null);
        escaped.setHolderUserId(null);
        when(batteryDao.selectList(any())).thenReturn(List.of(escaped));

        ReconcileService.CheckResult result = service.checkEscapedBatteries();

        assertThat(result.violations()).isEqualTo(1);
        assertThat(result.samples()).hasSize(1);
    }

    @Test
    @DisplayName("⑧ 调拨台账不一致（OUT 明细电池仍在仓）：检出")
    void 调拨台账不一致检出() {
        com.swapops.server.transfer.entity.TransferTaskEntity task =
                new com.swapops.server.transfer.entity.TransferTaskEntity();
        task.setTaskNo("TR1");
        task.setStatus(com.swapops.server.transfer.enums.TransferStatus.EXECUTING.getCode());
        when(transferTaskDao.selectList(any())).thenReturn(List.of(task));
        com.swapops.server.transfer.entity.TransferTaskItemEntity item =
                new com.swapops.server.transfer.entity.TransferTaskItemEntity();
        item.setTaskNo("TR1");
        item.setBatteryNo("BAT-X");
        item.setStatus(com.swapops.server.transfer.enums.TransferItemStatus.OUT.getCode());
        when(transferTaskItemDao.selectList(any())).thenReturn(List.of(item));
        com.swapops.server.device.entity.BatteryEntity battery =
                new com.swapops.server.device.entity.BatteryEntity();
        battery.setBatteryNo("BAT-X");
        battery.setCellId(99L); // 应处于在途却仍在仓
        when(batteryDao.selectOne(any())).thenReturn(battery);

        ReconcileService.CheckResult result = service.checkTransferLedger();

        assertThat(result.violations()).isEqualTo(1);
    }

    @Test
    @DisplayName("⑨ Agent 建议单悬挂（EXECUTING 超 5 分钟）：检出")
    void 悬挂建议单检出() {
        com.swapops.server.agent.entity.AgentActionEntity action =
                new com.swapops.server.agent.entity.AgentActionEntity();
        action.setActionNo("AA1");
        action.setActionType("RUN_RECONCILE");
        action.setStatus(5);
        when(agentActionDao.selectList(any())).thenReturn(List.of(action));

        assertThat(service.checkStaleAgentActions().violations()).isEqualTo(1);
    }

    @Test
    @DisplayName("⑦ 电池计数与流水不一致：检出")
    void 电池计数不一致检出() {
        com.swapops.server.device.entity.BatteryEntity battery =
                new com.swapops.server.device.entity.BatteryEntity();
        battery.setBatteryNo("BAT-X");
        battery.setSwaps(3);
        battery.setCycleCount(1);
        when(batteryDao.selectList(any())).thenReturn(List.of(battery));
        when(batteryCycleService.countByAction("BAT-X", "OUT")).thenReturn(2L);
        when(batteryCycleService.countByAction("BAT-X", "IN")).thenReturn(1L);

        ReconcileService.CheckResult result = service.checkBatteryCounters();

        assertThat(result.violations()).isEqualTo(1);
        assertThat(result.samples()).hasSize(1);
    }

    @Test
    @DisplayName("⑤ 超龄 PENDING 指令：检出")
    void 超龄指令检出() {
        CommandLogEntity cmd = new CommandLogEntity();
        cmd.setCabinetNo("SWAP-C-001");
        cmd.setCommandSeq(7L);
        when(commandLogDao.selectList(any())).thenReturn(List.of(cmd));

        assertThat(service.checkAgingCommands().violations()).isEqualTo(1);
    }
}
