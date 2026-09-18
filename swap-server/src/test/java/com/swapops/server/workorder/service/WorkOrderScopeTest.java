package com.swapops.server.workorder.service;

import com.swapops.server.admin.data.DataScopeSupport;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminContext;
import com.swapops.server.asset.service.DeviceOwnershipService;
import com.swapops.server.common.RRException;
import com.swapops.server.common.delay.DelayQueueService;
import com.swapops.server.common.id.SnowflakeIdGenerator;
import com.swapops.server.user.service.UserMessageService;
import com.swapops.server.workorder.config.WorkOrderProperties;
import com.swapops.server.workorder.dao.WorkOrderDao;
import com.swapops.server.workorder.dao.WorkOrderLogDao;
import com.swapops.server.workorder.entity.WorkOrderEntity;
import com.swapops.server.alarm.dao.AlarmDao;
import com.swapops.server.alarm.service.AlarmService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 工单数据范围测试（S8 批次29）：受限身份的详情/动作入口必须 fail-closed 越域 403。
 * require() 是所有动作与详情的唯一取数入口，故本组断言即覆盖全部工单写路径。
 */
@DisplayName("工单数据范围（越域 403 / 本域放行 / 无归属拒绝）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkOrderScopeTest {

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
    @Mock
    private UserMessageService messageService;
    @Mock
    private DeviceOwnershipService ownershipService;

    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        service = new WorkOrderService(workOrderDao, workOrderLogDao, alarmDao, alarmService,
                delayQueueService, idGenerator, new WorkOrderProperties(), messageService, ownershipService);
    }

    @AfterEach
    void tearDown() {
        AdminContext.set(null); // 防线程复用残留身份
    }

    private WorkOrderEntity order(Long stationId) {
        WorkOrderEntity order = new WorkOrderEntity();
        order.setId(1L);
        order.setWoNo("WO1");
        order.setStatus(1);
        order.setStationId(stationId);
        return order;
    }

    private void stationScoped(Long... stationIds) {
        AdminContext.set(new AdminContext.Principal(9L, "ops01", AdminRole.OPS, false,
                "STATION", Set.of(stationIds)));
    }

    @Test
    @DisplayName("本域站点：放行")
    void 本域可访问() {
        stationScoped(7L);
        when(workOrderDao.selectById(1L)).thenReturn(order(7L));

        assertThat(service.require(1L).getWoNo()).isEqualTo("WO1");
    }

    @Test
    @DisplayName("越域站点：403")
    void 越域拒绝() {
        stationScoped(7L);
        when(workOrderDao.selectById(1L)).thenReturn(order(99L));

        assertThatThrownBy(() -> service.require(1L))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无权访问该站点数据");
    }

    @Test
    @DisplayName("无站点归属（系统级工单）：受限身份一律拒绝（fail-closed）")
    void 无归属拒绝受限身份() {
        stationScoped(7L);
        when(workOrderDao.selectById(1L)).thenReturn(order(null));

        assertThatThrownBy(() -> service.require(1L))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无权访问该站点数据");
    }

    @Test
    @DisplayName("范围解析结果为空集：任何工单都不可见（fail-closed 空集）")
    void 空范围一律拒绝() {
        stationScoped();
        when(workOrderDao.selectById(1L)).thenReturn(order(7L));

        assertThatThrownBy(() -> service.require(1L)).isInstanceOf(RRException.class);
    }

    @Test
    @DisplayName("ALL 范围与 bootstrap 不受限；无身份（调度线程）不受限")
    void 不受限身份放行() {
        when(workOrderDao.selectById(1L)).thenReturn(order(99L));

        AdminContext.set(new AdminContext.Principal(9L, "ops01", AdminRole.OPS, false, "ALL", null));
        assertThat(service.require(1L)).isNotNull();

        AdminContext.set(new AdminContext.Principal(null, "bootstrap", AdminRole.SUPER, true, "ALL", null));
        assertThat(service.require(1L)).isNotNull();

        AdminContext.set(null);
        assertThat(service.require(1L)).isNotNull();
    }

    @Test
    @DisplayName("工单实体已有 stationId 字段（S8 迁移列）")
    void 归属列存在() {
        assertThat(DataScopeSupport.stationIdsOrNull()).isNull();
        assertThat(order(5L).getStationId()).isEqualTo(5L);
    }
}
