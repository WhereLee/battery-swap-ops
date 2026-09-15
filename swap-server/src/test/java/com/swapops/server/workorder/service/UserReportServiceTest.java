package com.swapops.server.workorder.service;

import com.swapops.server.workorder.entity.WorkOrderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户报障入口单测（S7 WP-D）：窗口内近重返回既有单；无记录则建单并写去重键；Redis 异常 fail-open。
 */
@DisplayName("用户报障入口（去重）")
@ExtendWith(MockitoExtension.class)
class UserReportServiceTest {

    @Mock
    private WorkOrderService workOrderService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private UserReportService service;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new UserReportService(workOrderService, stringRedisTemplate);
    }

    private WorkOrderEntity order(String woNo) {
        WorkOrderEntity order = new WorkOrderEntity();
        order.setWoNo(woNo);
        return order;
    }

    @Test
    @DisplayName("窗口内近重：返回既有工单，不新建")
    void 近重返回既有() {
        when(valueOperations.get(anyString())).thenReturn("WO-OLD");
        when(workOrderService.byWoNo("WO-OLD")).thenReturn(order("WO-OLD"));

        WorkOrderEntity result = service.report(7L, "SWAP-C-001", 3, "DEVICE_FAULT", "门打不开");

        assertThat(result.getWoNo()).isEqualTo("WO-OLD");
        verify(workOrderService, never()).createFromUserReport(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("无记录：建单并写去重键")
    void 建单写键() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(workOrderService.createFromUserReport(eq(7L), eq("SWAP-C-001"), eq(3),
                eq("DEVICE_FAULT"), any())).thenReturn(order("WO-NEW"));

        WorkOrderEntity result = service.report(7L, "SWAP-C-001", 3, "DEVICE_FAULT", "门打不开");

        assertThat(result.getWoNo()).isEqualTo("WO-NEW");
        verify(valueOperations).set(anyString(), eq("WO-NEW"), any());
    }

    @Test
    @DisplayName("Redis 异常 fail-open：照常建单")
    void redis异常放行() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));
        when(workOrderService.createFromUserReport(any(), any(), any(), any(), any()))
                .thenReturn(order("WO-NEW2"));

        WorkOrderEntity result = service.report(7L, "SWAP-C-002", null, "OTHER", null);

        assertThat(result.getWoNo()).isEqualTo("WO-NEW2");
    }
}
