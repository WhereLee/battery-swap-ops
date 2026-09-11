package com.swapops.server.workorder.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.alarm.dao.AlarmDao;
import com.swapops.server.alarm.entity.AlarmEntity;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.common.RRException;
import com.swapops.server.common.delay.DelayQueueService;
import com.swapops.server.common.id.SnowflakeIdGenerator;
import com.swapops.server.workorder.config.WorkOrderProperties;
import com.swapops.server.workorder.dao.WorkOrderDao;
import com.swapops.server.workorder.dao.WorkOrderLogDao;
import com.swapops.server.workorder.entity.WorkOrderEntity;
import com.swapops.server.workorder.entity.WorkOrderLogEntity;
import com.swapops.server.workorder.enums.WorkOrderStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单服务单测（S4.4）：转单幂等、状态机 CAS、SLA 撤销/超时告警、非法流转拒绝。
 */
@DisplayName("工单闭环")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkOrderServiceTest {

    @Mock
    private WorkOrderDao workOrderDao;
    @Mock
    private WorkOrderLogDao workOrderLogDao;
    @Mock
    private AlarmDao alarmDao;
    @Mock
    private AlarmService alarmService;
    @Mock
    private DelayQueueService delayQueueService;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    private WorkOrderService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WorkOrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, WorkOrderLogEntity.class);
        TableInfoHelper.initTableInfo(assistant, AlarmEntity.class);
    }

    @BeforeEach
    void setUp() {
        when(idGenerator.nextIdString()).thenReturn("123");
        service = new WorkOrderService(workOrderDao, workOrderLogDao, alarmDao, alarmService,
                delayQueueService, idGenerator, new WorkOrderProperties());
    }

    private AlarmEntity alarm() {
        AlarmEntity alarm = new AlarmEntity();
        alarm.setId(9L);
        alarm.setDeviceType("CABINET");
        alarm.setDeviceNo("SWAP-C-001");
        alarm.setAlarmType("CABINET_FAULT");
        return alarm;
    }

    private WorkOrderEntity order(int status) {
        WorkOrderEntity order = new WorkOrderEntity();
        order.setId(1L);
        order.setWoNo("WO123");
        order.setStatus(status);
        order.setSeverity("HIGH");
        order.setSlaDeadline(System.currentTimeMillis() + 60_000);
        return order;
    }

    @Test
    @DisplayName("告警转单：HIGH 默认（柜故障）+ SLA 定时登记 + CREATE 留痕")
    void 转单() {
        when(alarmDao.selectById(9L)).thenReturn(alarm());
        when(workOrderDao.selectOne(any())).thenReturn(null);
        when(workOrderDao.insert(any(WorkOrderEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, WorkOrderEntity.class).setId(1L);
            return 1;
        });

        WorkOrderEntity created = service.createFromAlarm(9L, null);

        assertThat(created.getWoNo()).isEqualTo("WO123");
        assertThat(created.getSeverity()).isEqualTo("HIGH");
        assertThat(created.getStatus()).isEqualTo(WorkOrderStatus.OPEN.getCode());
        verify(workOrderLogDao).insert(any(WorkOrderLogEntity.class));
        verify(delayQueueService).enqueue(eq(WorkOrderService.SLA_TOPIC), eq("WO123"),
                contains("WO123"), anyLong());
    }

    @Test
    @DisplayName("转单幂等：同告警重复转换返回既有工单（唯一键兜底）")
    void 转单幂等() {
        when(alarmDao.selectById(9L)).thenReturn(alarm());
        when(workOrderDao.selectOne(any())).thenReturn(order(WorkOrderStatus.OPEN.getCode()));

        WorkOrderEntity result = service.createFromAlarm(9L, "HIGH");

        assertThat(result.getWoNo()).isEqualTo("WO123");
        verify(workOrderDao, never()).insert(any(WorkOrderEntity.class));
        verify(delayQueueService, never()).enqueue(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("并发插入冲突：DuplicateKey 后重读返回既有")
    void 并发冲突重读() {
        when(alarmDao.selectById(9L)).thenReturn(alarm());
        when(workOrderDao.selectOne(any())).thenReturn(null, order(WorkOrderStatus.OPEN.getCode()));
        when(workOrderDao.insert(any(WorkOrderEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_wo_alarm"));

        WorkOrderEntity result = service.createFromAlarm(9L, "LOW");

        assertThat(result.getWoNo()).isEqualTo("WO123");
    }

    @Test
    @DisplayName("非法流转：终态工单再分诊 → 拒绝（CAS 未命中）")
    void 非法流转拒绝() {
        when(workOrderDao.selectById(1L)).thenReturn(order(WorkOrderStatus.CLOSED.getCode()));
        when(workOrderDao.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.triage(1L, "HIGH", "x"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("不允许");
    }

    @Test
    @DisplayName("流转成功：分诊→派单→处置→验收（撤销 SLA）→关闭")
    void 全链路流转() {
        when(workOrderDao.selectById(1L)).thenReturn(order(WorkOrderStatus.OPEN.getCode()));
        when(workOrderDao.update(isNull(), any())).thenReturn(1);

        service.triage(1L, "HIGH", "确认");
        service.assign(1L, 7L, "派给7");
        service.start(1L, "开始");
        service.verify(1L, "验收");
        service.close(1L, "关闭");

        verify(workOrderLogDao, org.mockito.Mockito.times(5)).insert(any(WorkOrderLogEntity.class));
        verify(delayQueueService, org.mockito.Mockito.times(2)).cancel(WorkOrderService.SLA_TOPIC, "WO123");
    }

    @Test
    @DisplayName("SLA 超时：活跃工单置位 + WORK_ORDER_SLA_BREACH 告警；终态不误报")
    void SLA超时() {
        when(workOrderDao.selectOne(any())).thenReturn(order(WorkOrderStatus.HANDLING.getCode()));
        when(workOrderDao.update(isNull(), any())).thenReturn(1);

        assertThat(service.markSlaBreached("WO123")).isTrue();
        verify(alarmService).raise(anyString(), eq("WO123"),
                eq(com.swapops.server.alarm.AlarmType.WORK_ORDER_SLA_BREACH), anyString());

        when(workOrderDao.selectOne(any())).thenReturn(order(WorkOrderStatus.CLOSED.getCode()));
        assertThat(service.markSlaBreached("WO123")).isFalse();
    }
}
