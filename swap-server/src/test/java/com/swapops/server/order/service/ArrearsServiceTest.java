package com.swapops.server.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.common.RRException;
import com.swapops.server.order.dao.ArrearsRecordDao;
import com.swapops.server.order.entity.ArrearsRecordEntity;
import com.swapops.server.user.service.UserMessageService;
import com.swapops.server.user.service.WalletService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 欠费闭环单测（S7 WP-D）：落单/累加/门槛/补缴/减免/告警恢复。
 */
@DisplayName("欠费闭环")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArrearsServiceTest {

    @Mock
    private ArrearsRecordDao arrearsRecordDao;
    @Mock
    private WalletService walletService;
    @Mock
    private AlarmService alarmService;
    @Mock
    private UserMessageService messageService;

    private ArrearsService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ArrearsRecordEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new ArrearsService(arrearsRecordDao, walletService, alarmService, messageService);
    }

    private ArrearsRecordEntity record(int amount, int settled, int status) {
        ArrearsRecordEntity record = new ArrearsRecordEntity();
        record.setId(9L);
        record.setUserId(7L);
        record.setOrderId(99L);
        record.setOrderNo("SWO-1");
        record.setAmountFen(amount);
        record.setSettledFen(settled);
        record.setStatus(status);
        return record;
    }

    @Test
    @DisplayName("落单：首次插入 + 站内信；金额<=0 忽略")
    void 落单() {
        when(arrearsRecordDao.selectOne(any())).thenReturn(null);
        when(arrearsRecordDao.insert(any(ArrearsRecordEntity.class))).thenReturn(1);

        service.recordShortfall(7L, 99L, "SWO-1", 100);
        verify(arrearsRecordDao).insert(any(ArrearsRecordEntity.class));
        verify(messageService).send(eq(7L), eq("ARREARS"), any(), any());

        service.recordShortfall(7L, 99L, "SWO-1", 0);
        verify(arrearsRecordDao, org.mockito.Mockito.times(1)).insert(any(ArrearsRecordEntity.class));
    }

    @Test
    @DisplayName("落单：已存在 → 累加（同订单不重复计欠）")
    void 累加() {
        when(arrearsRecordDao.selectOne(any())).thenReturn(record(100, 0, 1));
        service.recordShortfall(7L, 99L, "SWO-1", 200);
        verify(arrearsRecordDao).update(isNull(), any());
        verify(arrearsRecordDao, never()).insert(any(ArrearsRecordEntity.class));
    }

    @Test
    @DisplayName("门槛：存在 OPEN 欠费")
    void 门槛() {
        when(arrearsRecordDao.selectCount(any())).thenReturn(1L);
        assertThat(service.hasOpenArrears(7L)).isTrue();
        when(arrearsRecordDao.selectCount(any())).thenReturn(0L);
        assertThat(service.hasOpenArrears(7L)).isFalse();
    }

    @Test
    @DisplayName("补缴：余额扣款 + CAS 结清 + 关告警 + 站内信")
    void 补缴() {
        when(arrearsRecordDao.selectById(9L)).thenReturn(record(100, 0, 1),
                record(100, 100, 2));
        when(walletService.deductBalance(7L, 100)).thenReturn(true);
        when(arrearsRecordDao.update(isNull(), any())).thenReturn(1);

        ArrearsRecordEntity result = service.pay(7L, 9L);

        assertThat(result.getStatus()).isEqualTo(2);
        verify(walletService).deductBalance(7L, 100);
        verify(alarmService).markRecovered(AlarmService.DEVICE_ORDER, "SWO-1", AlarmType.ORDER_ARREARS);
        verify(messageService).send(eq(7L), eq("ARREARS"), any(), any());
    }

    @Test
    @DisplayName("补缴：余额不足拒绝；非本人拒绝；已结清幂等返回")
    void 补缴边界() {
        ArrearsRecordEntity other = record(100, 0, 1);
        other.setUserId(8L);
        when(arrearsRecordDao.selectById(9L)).thenReturn(other);
        assertThatThrownBy(() -> service.pay(7L, 9L))
                .isInstanceOf(RRException.class).hasMessageContaining("无权");

        when(arrearsRecordDao.selectById(9L)).thenReturn(record(100, 0, 1));
        when(walletService.deductBalance(7L, 100)).thenReturn(false);
        assertThatThrownBy(() -> service.pay(7L, 9L))
                .isInstanceOf(RRException.class).hasMessageContaining("余额不足");

        when(arrearsRecordDao.selectById(9L)).thenReturn(record(100, 100, 2));
        assertThat(service.pay(7L, 9L).getStatus()).isEqualTo(2);
    }

    @Test
    @DisplayName("减免：结清 + 关告警 + 站内信（客服语义）")
    void 减免() {
        when(arrearsRecordDao.selectById(9L)).thenReturn(record(200, 0, 1), record(200, 200, 2));
        when(arrearsRecordDao.update(isNull(), any())).thenReturn(1);

        service.waive(9L, "客诉补偿");

        verify(alarmService, atLeastOnce()).markRecovered(any(), any(), any());
        verify(messageService).send(eq(7L), eq("ARREARS"), any(), any());
    }
}
