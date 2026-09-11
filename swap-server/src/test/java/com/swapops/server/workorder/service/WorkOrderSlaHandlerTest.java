package com.swapops.server.workorder.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 工单 SLA 处理器单测（S4.4）：合法载荷转服务处理，垃圾载荷安全跳过。
 */
@DisplayName("工单 SLA 处理器")
@ExtendWith(MockitoExtension.class)
class WorkOrderSlaHandlerTest {

    @Mock
    private WorkOrderService workOrderService;

    @Test
    @DisplayName("合法载荷：交服务置超时")
    void 合法载荷() {
        new WorkOrderSlaHandler(workOrderService).handle("{\"woNo\":\"WO1\"}");

        verify(workOrderService).markSlaBreached("WO1");
    }

    @Test
    @DisplayName("非法载荷：跳过不动作")
    void 非法载荷() {
        WorkOrderSlaHandler handler = new WorkOrderSlaHandler(workOrderService);
        handler.handle("not-json");
        handler.handle("{}");

        verifyNoInteractions(workOrderService);
    }
}
