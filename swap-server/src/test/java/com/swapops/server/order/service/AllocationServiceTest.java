package com.swapops.server.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.contract.BatteryStatus;
import com.swapops.contract.CellStatus;
import com.swapops.server.common.RRException;
import com.swapops.server.config.BillingProperties;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分配引擎单测：Lua 原子弹仓 + DB CAS 兜底 + 内容校验回滚重试 + 柜不可用拒绝。
 */
@DisplayName("分配引擎（预占两层保障）")
@ExtendWith(MockitoExtension.class)
class AllocationServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private CabinetDao cabinetDao;
    @Mock
    private CellDao cellDao;
    @Mock
    private BatteryDao batteryDao;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private AllocationService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CellEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new AllocationService(stringRedisTemplate, cabinetDao, cellDao, batteryDao,
                new BillingProperties());
    }

    private CabinetEntity cabinet(int status) {
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setId(1L);
        cabinet.setCabinetNo("SWAP-C-001");
        cabinet.setStatus(status);
        return cabinet;
    }

    private CellEntity cell(long id, long cabinetId, int cellNo, CellStatus status, Long batteryId) {
        CellEntity cell = new CellEntity();
        cell.setId(id);
        cell.setCabinetId(cabinetId);
        cell.setCellNo(cellNo);
        cell.setStatus(status.getCode());
        cell.setBatteryId(batteryId);
        return cell;
    }

    private BatteryEntity battery(long id, long cellId, BatteryStatus status) {
        BatteryEntity battery = new BatteryEntity();
        battery.setId(id);
        battery.setCellId(cellId);
        battery.setStatus(status.getCode());
        battery.setBatteryNo("BAT-0001");
        return battery;
    }

    @Test
    @DisplayName("满电路径：Lua 弹仓 → DB 锁 CAS → 内容校验 → 预占键落 Redis")
    void 分配满电成功() {
        when(cabinetDao.selectOne(any())).thenReturn(cabinet(1));
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn("11");
        when(cellDao.update(isNull(), any())).thenReturn(1);
        when(cellDao.selectById(11L)).thenReturn(cell(11L, 1L, 3, CellStatus.OCCUPIED, 21L));
        when(batteryDao.selectById(21L)).thenReturn(battery(21L, 11L, BatteryStatus.FULL));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        AllocationService.AllocResult result = service.allocate("SWAP-C-001", 99L, "SWO-1", true);

        assertThat(result.cell().getId()).isEqualTo(11L);
        assertThat(result.battery().getId()).isEqualTo(21L);
        verify(valueOperations).set(eq("swap:preempt:SWO-1"), eq("11"), eq(120L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("DB 锁竞争：候选被抢 → 删 Redis 锁并弹下一个")
    void db锁竞争重试() {
        when(cabinetDao.selectOne(any())).thenReturn(cabinet(1));
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn("11", "12");
        when(cellDao.update(isNull(), any())).thenReturn(0, 1);
        when(cellDao.selectById(12L)).thenReturn(cell(12L, 1L, 4, CellStatus.OCCUPIED, 22L));
        when(batteryDao.selectById(22L)).thenReturn(battery(22L, 12L, BatteryStatus.FULL));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        AllocationService.AllocResult result = service.allocate("SWAP-C-001", 99L, "SWO-1", true);

        assertThat(result.cell().getId()).isEqualTo(12L);
        verify(stringRedisTemplate).delete("swap:cell-lock:11");
    }

    @Test
    @DisplayName("内容失效（电池非满电）：回滚锁并重试下一个")
    void 内容失效重试() {
        when(cabinetDao.selectOne(any())).thenReturn(cabinet(1));
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn("11", "12");
        when(cellDao.update(isNull(), any())).thenReturn(1);
        when(cellDao.selectById(11L)).thenReturn(cell(11L, 1L, 3, CellStatus.OCCUPIED, 21L));
        when(cellDao.selectById(12L)).thenReturn(cell(12L, 1L, 4, CellStatus.OCCUPIED, 22L));
        when(batteryDao.selectById(21L)).thenReturn(battery(21L, 11L, BatteryStatus.CHARGING));
        when(batteryDao.selectById(22L)).thenReturn(battery(22L, 12L, BatteryStatus.FULL));
        when(cabinetDao.selectById(1L)).thenReturn(cabinet(1));
        when(stringRedisTemplate.opsForSet()).thenReturn(org.mockito.Mockito.mock(
                org.springframework.data.redis.core.SetOperations.class));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        AllocationService.AllocResult result = service.allocate("SWAP-C-001", 99L, "SWO-1", true);

        assertThat(result.battery().getId()).isEqualTo(22L);
        // 第一次锁 CAS + 失效回滚解锁 + 第二次锁 CAS
        verify(cellDao, org.mockito.Mockito.atLeast(2)).update(isNull(), any());
        verify(stringRedisTemplate).delete("swap:cell-lock:11");
    }

    @Test
    @DisplayName("无资源：Lua 弹空 → 明确业务错误")
    void 无资源报错() {
        when(cabinetDao.selectOne(any())).thenReturn(cabinet(1));
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        assertThatThrownBy(() -> service.allocate("SWAP-C-001", 99L, "SWO-1", true))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("暂无");
    }

    @Test
    @DisplayName("柜维护/停用：拒绝分配（不碰 Redis）")
    void 柜不可用拒绝() {
        when(cabinetDao.selectOne(any())).thenReturn(cabinet(4));

        assertThatThrownBy(() -> service.allocate("SWAP-C-001", 99L, "SWO-1", true))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("不可用");
    }

    @Test
    @DisplayName("空仓路径（RETURN）：EMPTY 仓可分配")
    void 空仓路径() {
        when(cabinetDao.selectOne(any())).thenReturn(cabinet(1));
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn("15");
        when(cellDao.update(isNull(), any())).thenReturn(1);
        when(cellDao.selectById(15L)).thenReturn(cell(15L, 1L, 7, CellStatus.EMPTY, null));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        AllocationService.AllocResult result = service.allocate("SWAP-C-001", 99L, "SWO-2", false);

        assertThat(result.cell().getId()).isEqualTo(15L);
        assertThat(result.battery()).isNull();
    }
}
