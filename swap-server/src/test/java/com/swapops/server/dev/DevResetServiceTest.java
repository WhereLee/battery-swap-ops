package com.swapops.server.dev;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.contract.OrderStatus;
import com.swapops.server.device.config.SwapRedisKeys;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.AllocationService;
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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * dev 重置单测（S4-pre）：活跃订单取消、电池归位/额外汇总、分配池重建。
 */
@DisplayName("联调数据重置")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DevResetServiceTest {

    @Mock
    private SwapOrderDao orderDao;
    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private CellDao cellDao;
    @Mock
    private BatteryDao batteryDao;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private AllocationService allocationService;
    @Mock
    private com.swapops.server.user.dao.WalletDao walletDao;
    @Mock
    private com.swapops.server.user.dao.UserPlanDao userPlanDao;
    @Mock
    private com.swapops.server.user.dao.SwapUserDao swapUserDao;

    private DevProperties devProperties;
    private DevResetService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SwapOrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, CabinetEntity.class);
        TableInfoHelper.initTableInfo(assistant, CellEntity.class);
        TableInfoHelper.initTableInfo(assistant, BatteryEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.user.entity.WalletEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.user.entity.UserPlanEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.user.entity.SwapUserEntity.class);
    }

    @BeforeEach
    void setUp() {
        devProperties = new DevProperties();
        devProperties.setCellsPerCabinet(12);
        devProperties.setFullCells(2);
        service = new DevResetService(orderDao, cabinetDao, cellDao, batteryDao,
                stringRedisTemplate, allocationService, devProperties,
                walletDao, userPlanDao, swapUserDao);
        when(walletDao.update(isNull(), any())).thenReturn(5);
        when(userPlanDao.update(isNull(), any())).thenReturn(5);
        when(swapUserDao.selectOne(any())).thenReturn(null);
    }

    private void stubCabinetAndCells() {
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(1L);
        cabinet.setCabinetNo("SWAP-C-001");
        when(cabinetDao.selectList(any())).thenReturn(List.of(cabinet));

        AtomicLong cellId = new AtomicLong(1000);
        when(cellDao.selectOne(any())).thenAnswer(inv -> {
            CellEntity cell = new CellEntity();
            cell.setId(cellId.incrementAndGet());
            cell.setCabinetId(1L);
            return cell;
        });
        BatteryEntity battery = new BatteryEntity();
        battery.setId(21L);
        battery.setBatteryNo("BAT-0001");
        when(batteryDao.selectOne(any())).thenReturn(battery);
        when(batteryDao.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("重置：活跃订单取消 + 前 N 仓归位 + 池重建")
    void 重置主流程() {
        SwapOrderEntity active = new SwapOrderEntity();
        active.setId(99L);
        active.setOrderNo("SWO-1");
        active.setStatus(OrderStatus.PENDING_OPEN.getCode());
        when(orderDao.selectList(any())).thenReturn(List.of(active));
        when(orderDao.update(isNull(), any())).thenReturn(1);
        stubCabinetAndCells();

        Map<String, Object> result = service.reset();

        assertThat(result.get("ordersCancelled")).isEqualTo(1);
        assertThat(result.get("cellsOccupied")).isEqualTo(2);
        assertThat(result.get("extrasParked")).isEqualTo(0);
        verify(stringRedisTemplate).delete(eq(SwapRedisKeys.PREEMPT_PREFIX + "SWO-1"));
        // 注：NOT IN 的 SQL 过滤无法用 mock 验证，由实机剧本 _c0 覆盖（回归时异常为分配池为空）
        verify(allocationService).rebuildFromDb();
    }

    @Test
    @DisplayName("重置：非种子电池（逃逸/在途）脱仓停用；再次执行幂等（无活跃单可取消）")
    void 额外电池与幂等() {
        when(orderDao.selectList(any())).thenReturn(List.of());
        stubCabinetAndCells();
        BatteryEntity extra = new BatteryEntity();
        extra.setId(500L);
        extra.setBatteryNo("BAT-9999");
        when(batteryDao.selectList(any())).thenReturn(List.of(extra));

        Map<String, Object> result = service.reset();

        assertThat(result.get("ordersCancelled")).isEqualTo(0);
        assertThat(result.get("extrasParked")).isEqualTo(1);
        verify(batteryDao, org.mockito.Mockito.atLeast(3)).update(isNull(), any());
        verify(allocationService).rebuildFromDb();
    }

    @Test
    @DisplayName("重置：种子仓被旧电池占位 → 先脱旧占位再绑种子（S5 修复，防 uk_battery_cell 冲突）")
    void 旧占位先脱后绑() {
        when(orderDao.selectList(any())).thenReturn(List.of());
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(1L);
        cabinet.setCabinetNo("SWAP-C-001");
        when(cabinetDao.selectList(any())).thenReturn(List.of(cabinet));

        AtomicLong cellId = new AtomicLong(1000);
        when(cellDao.selectOne(any())).thenAnswer(inv -> {
            CellEntity cell = new CellEntity();
            cell.setId(cellId.incrementAndGet());
            cell.setCabinetId(1L);
            cell.setBatteryId(99L); // 旧电池占着种子仓
            return cell;
        });
        BatteryEntity seed = new BatteryEntity();
        seed.setId(21L);
        seed.setBatteryNo("BAT-0001");
        when(batteryDao.selectOne(any())).thenReturn(seed);
        when(batteryDao.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.reset();

        assertThat(result.get("cellsOccupied")).isEqualTo(2);
        // 本桩下 12 仓全部被旧电池占位：2 满仓(脱旧+绑种)×2 + 10 空仓(脱旧)×1 = 14 次电池更新；
        // 顺序正确性由实机剧本 _c16 兜底（真库 uk_battery_cell 冲突即失败）
        verify(batteryDao, org.mockito.Mockito.times(14)).update(isNull(), any());
        verify(allocationService).rebuildFromDb();
    }
}
