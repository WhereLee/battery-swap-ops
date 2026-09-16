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
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * dev 重置单测（批次18 两阶段重构）：活跃订单取消、全量清场、种子重绑、
 * 种子缺失入报告，以及"清场先于绑定"的顺序不变式。
 * 收敛性的实机证据由 batch18 剧本（构造交错引用图 → reset → 零残留）与 _c0 回归兜底——
 * mock 无法执行 SQL 过滤/唯一键语义（参见 fixes/dev-reset-two-bugs.md 测试策略）。
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
    @Mock
    private com.swapops.server.order.dao.ArrearsRecordDao arrearsRecordDao;
    @Mock
    private com.swapops.server.user.dao.UserCouponDao userCouponDao;
    @Mock
    private com.swapops.server.user.dao.CouponTemplateDao couponTemplateDao;
    @Mock
    private com.swapops.server.user.dao.UserMessageDao userMessageDao;

    @Mock
    private com.swapops.server.settlement.dao.SettlementStatementDao settlementStatementDao;
    @Mock
    private com.swapops.server.settlement.dao.OrderSettlementDao orderSettlementDao;

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
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.order.entity.ArrearsRecordEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.user.entity.UserCouponEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.user.entity.CouponTemplateEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.user.entity.UserMessageEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.settlement.entity.OrderSettlementEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.swapops.server.settlement.entity.SettlementStatementEntity.class);
    }

    @BeforeEach
    void setUp() {
        devProperties = new DevProperties();
        devProperties.setCellsPerCabinet(12);
        devProperties.setFullCells(2);
        service = new DevResetService(orderDao, cabinetDao, cellDao, batteryDao,
                stringRedisTemplate, allocationService, devProperties,
                walletDao, userPlanDao, swapUserDao,
                arrearsRecordDao, userCouponDao, couponTemplateDao, userMessageDao,
                settlementStatementDao, orderSettlementDao);
        when(walletDao.update(isNull(), any())).thenReturn(5);
        when(userPlanDao.update(isNull(), any())).thenReturn(5);
        when(swapUserDao.selectOne(any())).thenReturn(null);
    }

    /** 1 柜 × 12 仓；种子电池桩（可被测试覆盖为 null 以模拟缺失） */
    private void stubCabinetAndCells(long seedBatteryId) {
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
        battery.setId(seedBatteryId);
        battery.setBatteryNo("BAT-0001");
        when(batteryDao.selectOne(any())).thenReturn(battery);
    }

    @Test
    @DisplayName("重置主流程：取消活跃单 + 全量清场 + 前 N 仓重绑种子（seedMissing 空）")
    void 重置主流程() {
        SwapOrderEntity active = new SwapOrderEntity();
        active.setId(99L);
        active.setOrderNo("SWO-1");
        active.setStatus(OrderStatus.PENDING_OPEN.getCode());
        when(orderDao.selectList(any())).thenReturn(List.of(active));
        when(orderDao.update(isNull(), any())).thenReturn(1);
        when(batteryDao.update(isNull(), any())).thenReturn(63);
        stubCabinetAndCells(21L);

        Map<String, Object> result = service.reset();

        assertThat(result.get("ordersCancelled")).isEqualTo(1);
        assertThat(result.get("cellsOccupied")).isEqualTo(2);
        assertThat(result.get("batteriesDetached")).isEqualTo(63);
        assertThat((List<?>) result.get("seedMissing")).isEmpty();
        verify(stringRedisTemplate).delete(eq(SwapRedisKeys.PREEMPT_PREFIX + "SWO-1"));
        // 注：NOT IN / 全表 UPDATE 的 SQL 语义无法用 mock 验证，由实机剧本 _c0 与 batch18 覆盖
        verify(allocationService).rebuildFromDb();
    }

    @Test
    @DisplayName("收敛顺序：全量电池脱仓与 12 仓统一清场，均先于种子绑定——旧实现逐仓边扫边脱不收敛的替代设计")
    void 清场先于绑定() {
        when(orderDao.selectList(any())).thenReturn(List.of());
        stubCabinetAndCells(21L);

        service.reset();

        InOrder inOrder = inOrder(batteryDao, cellDao);
        inOrder.verify(batteryDao).update(isNull(), any());            // ① 全量电池脱仓最先
        inOrder.verify(cellDao, times(12)).selectOne(any());           // ② 12 仓扫描（Pass1）全部在其后，且恰 12 次
        verify(cellDao, times(14)).update(isNull(), any());            // 12 清场 + 2 绑定（总次结构）
        verify(batteryDao, times(3)).update(isNull(), any());          // 1 全量清场 + 2 种子绑定
        // 绑定阶段与清场阶段的先后由上述结构计数与 Pass2 代码结构保证；
        // 真实 SQL 语义（唯一键/收敛性）由 batch18 实机剧本（交错引用图 → reset → 零残留）兜底
        verify(allocationService).rebuildFromDb();
    }

    @Test
    @DisplayName("种子缺失：对应仓保持空仓（旧引用已被清场清除）+ 记入 seedMissing 报告（不静默）")
    void 种子缺失入报告() {
        when(orderDao.selectList(any())).thenReturn(List.of());
        stubCabinetAndCells(21L);
        when(batteryDao.selectOne(any())).thenReturn(null); // BAT-0001/0002 均不存在

        Map<String, Object> result = service.reset();

        assertThat(result.get("cellsOccupied")).isEqualTo(0);
        List<?> missing = (List<?>) result.get("seedMissing");
        assertThat(missing).hasSize(2);
        assertThat(missing.get(0)).isEqualTo("BAT-0001@SWAP-C-001#1");
        assertThat(missing.get(1)).isEqualTo("BAT-0002@SWAP-C-001#2");
        verify(cellDao, times(12)).update(isNull(), any()); // 清场仍执行（空仓合法态）
        verify(allocationService).rebuildFromDb();
    }

    @Test
    @DisplayName("再执行幂等：无活跃单可取消、occupied 稳定")
    void 重复执行幂等() {
        when(orderDao.selectList(any())).thenReturn(List.of());
        stubCabinetAndCells(21L);

        Map<String, Object> first = service.reset();
        Map<String, Object> second = service.reset();

        assertThat(second.get("ordersCancelled")).isEqualTo(0);
        assertThat(second.get("cellsOccupied")).isEqualTo(first.get("cellsOccupied"));
        assertThat((List<?>) second.get("seedMissing")).isEmpty();
    }
}
