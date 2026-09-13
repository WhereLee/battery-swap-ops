package com.swapops.server.device.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.device.dao.BatteryCycleLogDao;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.entity.BatteryCycleLogEntity;
import com.swapops.server.device.entity.BatteryEntity;
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
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 电池循环计数单测（S4.1）：流水先行、计数器原子累加、重复事件不双计。
 */
@DisplayName("电池循环计数")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatteryCycleServiceTest {

    @Mock
    private BatteryCycleLogDao cycleLogDao;
    @Mock
    private BatteryDao batteryDao;

    private BatteryCycleService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BatteryCycleLogEntity.class);
        TableInfoHelper.initTableInfo(assistant, BatteryEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new BatteryCycleService(cycleLogDao, batteryDao);
    }

    @Test
    @DisplayName("OUT 事件：写流水 + swaps 原子 +1")
    void 取电计数() {
        when(cycleLogDao.insert(any(BatteryCycleLogEntity.class))).thenReturn(1);
        when(batteryDao.update(isNull(), any())).thenReturn(1);

        boolean first = service.record("BAT-0001", "OUT", 100, "SWAP-C-001", 7L, "boot-1", 42L);

        assertThat(first).isTrue();
        verify(cycleLogDao).insert(any(BatteryCycleLogEntity.class));
        verify(batteryDao).update(isNull(), any());
    }

    @Test
    @DisplayName("重复事件（唯一键冲突）：忽略且不计数")
    void 重复事件不双计() {
        when(cycleLogDao.insert(any(BatteryCycleLogEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_cycle_event"));

        boolean first = service.record("BAT-0001", "IN", 20, "SWAP-C-001", 8L, "boot-1", 43L);

        assertThat(first).isFalse();
        verify(batteryDao, never()).update(isNull(), any());
    }
}
