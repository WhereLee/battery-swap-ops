package com.swapops.server.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.contract.OrderStatus;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.common.RRException;
import com.swapops.server.config.BillingProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.service.CommandDispatchService;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.form.CreateOrderForm;
import com.swapops.server.user.entity.SwapUserEntity;
import com.swapops.server.user.entity.WalletEntity;
import com.swapops.server.user.service.PlanService;
import com.swapops.server.user.service.UserAccountService;
import com.swapops.server.user.service.WalletService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下单/取消单测：资格校验（持有人态、套餐余额、押金）、幂等重放、取消与超时关闭的补偿。
 */
@DisplayName("换电订单服务")
@ExtendWith(MockitoExtension.class)
class SwapOrderServiceTest {

    @Mock
    private SwapOrderDao orderDao;
    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private CellDao cellDao;
    @Mock
    private BatteryDao batteryDao;
    @Mock
    private StationDao stationDao;
    @Mock
    private UserAccountService userAccountService;
    @Mock
    private WalletService walletService;
    @Mock
    private PlanService planService;
    @Mock
    private AllocationService allocationService;
    @Mock
    private CommandDispatchService commandDispatchService;

    private SwapOrderService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SwapOrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, CellEntity.class);
        TableInfoHelper.initTableInfo(assistant, BatteryEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new SwapOrderService(orderDao, cabinetDao, cellDao, batteryDao, stationDao,
                userAccountService, walletService, planService, allocationService,
                commandDispatchService, new BillingProperties());
    }

    private CreateOrderForm form(String type, String cabinetNo) {
        CreateOrderForm form = new CreateOrderForm();
        form.setType(type);
        form.setCabinetNo(cabinetNo);
        return form;
    }

    private WalletEntity wallet(int balance, int deposit) {
        WalletEntity wallet = new WalletEntity();
        wallet.setUserId(7L);
        wallet.setBalanceFen(balance);
        wallet.setDepositFen(deposit);
        return wallet;
    }

    private void stubCreateCommon() {
        when(userAccountService.requireActive(7L)).thenReturn(new SwapUserEntity());
        when(walletService.getByUserId(7L)).thenReturn(wallet(20000, 0));
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(null);
        when(orderDao.insert(any(SwapOrderEntity.class))).thenReturn(1);
        CellEntity cell = new CellEntity();
        cell.setId(11L);
        cell.setCellNo(3);
        cell.setCabinetId(1L);
        BatteryEntity battery = new BatteryEntity();
        battery.setId(21L);
        battery.setBatteryNo("BAT-0001");
        when(allocationService.allocate(eq("SWAP-C-001"), any(), anyString(), eq(true)))
                .thenReturn(new AllocationService.AllocResult(cell, battery));
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(1L);
        cabinet.setStationId(1L);
        cabinet.setCabinetNo("SWAP-C-001");
        when(cabinetDao.selectOne(any())).thenReturn(cabinet);
        when(cabinetDao.selectById(1L)).thenReturn(cabinet);
        when(orderDao.updateById(any(SwapOrderEntity.class))).thenReturn(1);
    }

    @Test
    @DisplayName("TAKE 下单成功：PENDING_OPEN + 分配预占 + 绑定柜/仓/电池")
    void 下单成功() {
        when(orderDao.selectOne(any())).thenReturn(null);
        when(batteryDao.selectOne(any())).thenReturn(null);
        stubCreateCommon();

        SwapOrderEntity order = service.create(7L, form("TAKE", "SWAP-C-001"), "idem-1");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_OPEN.getCode());
        assertThat(order.getCellId()).isEqualTo(11L);
        assertThat(order.getTakeBatteryId()).isEqualTo(21L);
        assertThat(order.getOrderNo()).startsWith("SW");
    }

    @Test
    @DisplayName("幂等重放：同 idemKey 返回原单，不重复分配")
    void 幂等重放() {
        SwapOrderEntity existing = new SwapOrderEntity();
        existing.setId(88L);
        existing.setOrderNo("SWO-OLD");
        existing.setIdemKey("idem-1");
        when(orderDao.selectOne(any())).thenReturn(existing);

        SwapOrderEntity order = service.create(7L, form("TAKE", "SWAP-C-001"), "idem-1");

        assertThat(order.getId()).isEqualTo(88L);
        verifyNoInteractions(allocationService, walletService, planService);
    }

    @Test
    @DisplayName("存在进行中订单：拒绝")
    void 有活跃单拒绝() {
        SwapOrderEntity active = new SwapOrderEntity();
        active.setOrderNo("SWO-ACTIVE");
        when(orderDao.selectOne(any())).thenReturn(null, active);
        when(userAccountService.requireActive(7L)).thenReturn(new SwapUserEntity());

        assertThatThrownBy(() -> service.create(7L, form("TAKE", "SWAP-C-001"), "idem-2"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("进行中的订单");
    }

    @Test
    @DisplayName("TAKE 已持有电池：拒绝（提示走 SWAP/RETURN）")
    void take已持有拒绝() {
        when(orderDao.selectOne(any())).thenReturn(null);
        when(userAccountService.requireActive(7L)).thenReturn(new SwapUserEntity());
        BatteryEntity held = new BatteryEntity();
        held.setBatteryNo("BAT-0009");
        when(batteryDao.selectOne(any())).thenReturn(held);

        assertThatThrownBy(() -> service.create(7L, form("TAKE", "SWAP-C-001"), "idem-3"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("已持有电池");
    }

    @Test
    @DisplayName("无套餐且余额不足：拒绝")
    void 资格不足拒绝() {
        when(orderDao.selectOne(any())).thenReturn(null);
        when(userAccountService.requireActive(7L)).thenReturn(new SwapUserEntity());
        when(batteryDao.selectOne(any())).thenReturn(null);
        when(walletService.getByUserId(7L)).thenReturn(wallet(100, 0));
        when(planService.findUsablePlan(eq(7L), anyLong())).thenReturn(null);

        assertThatThrownBy(() -> service.create(7L, form("TAKE", "SWAP-C-001"), "idem-4"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("余额不足");
    }

    @Test
    @DisplayName("取消：未开仓 → CANCELLED + 释放预占")
    void 取消释放预占() {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setOrderNo("SWO-1");
        order.setUserId(7L);
        order.setStatus(OrderStatus.PENDING_OPEN.getCode());
        order.setCellId(11L);
        when(orderDao.selectOne(any())).thenReturn(order);
        when(orderDao.update(any(), any())).thenReturn(1);
        when(orderDao.selectById(99L)).thenReturn(order);

        service.cancel("SWO-1", 7L);

        verify(allocationService).release(11L, 99L, "SWO-1");
    }

    @Test
    @DisplayName("取消：已开仓 → 拒绝（区分状态）")
    void 已开仓不可取消() {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setOrderNo("SWO-1");
        order.setUserId(7L);
        order.setStatus(OrderStatus.OPENED.getCode());
        when(orderDao.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> service.cancel("SWO-1", 7L))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("不可取消");
    }

    @Test
    @DisplayName("对账证据推进：PENDING_OPEN→OPENED（设备已执行开仓）")
    void 对账证据开仓() {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setStatus(OrderStatus.PENDING_OPEN.getCode());
        when(orderDao.update(any(), any())).thenReturn(1);

        assertThat(service.markOpenedByEvidence(order)).isTrue();
    }

    @Test
    @DisplayName("异常终止：活跃态→EXCEPTION + 释放预占；终态拒绝（幂等）")
    void 异常终止释放预占() {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setOrderNo("SWO-1");
        order.setStatus(OrderStatus.OPENED.getCode());
        order.setCellId(11L);
        when(orderDao.update(any(), any())).thenReturn(1);

        assertThat(service.markException(order, "CABINET_FAULT")).isTrue();
        verify(allocationService).release(11L, 99L, "SWO-1");

        SwapOrderEntity done = new SwapOrderEntity();
        done.setId(100L);
        done.setStatus(OrderStatus.COMPLETED.getCode());
        assertThat(service.markException(done, "X")).isFalse();
    }

    @Test
    @DisplayName("超时关闭：CAS 成功 → 释放预占")
    void 超时关闭释放() {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setOrderNo("SWO-1");
        order.setStatus(OrderStatus.OPENED.getCode());
        order.setCellId(11L);
        when(orderDao.update(any(), any())).thenReturn(1);

        boolean closed = service.closeTimedOut(order, "PICKUP_TIMEOUT");

        assertThat(closed).isTrue();
        verify(allocationService).release(11L, 99L, "SWO-1");
    }
}
