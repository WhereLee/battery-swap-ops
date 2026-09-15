package com.swapops.server.order.controller;

import com.swapops.contract.OrderStatus;
import com.swapops.server.common.RRException;
import com.swapops.server.order.entity.RefundRecordEntity;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.SwapOrderService;
import com.swapops.server.order.service.pay.RefundService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理端退款单测（S7 WP-0）：已完成订单拒绝人工退款；无可退金额拒绝；异常单正常走退款。
 */
@DisplayName("管理端退款（状态白名单）")
@ExtendWith(MockitoExtension.class)
class AdminRefundControllerTest {

    @Mock
    private SwapOrderService swapOrderService;
    @Mock
    private RefundService refundService;

    private SwapOrderEntity order(int status) {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setOrderNo("SWO-1");
        order.setUserId(7L);
        order.setStatus(status);
        return order;
    }

    private AdminRefundController controller() {
        return new AdminRefundController(swapOrderService, refundService);
    }

    @Test
    @DisplayName("COMPLETED 拒绝人工退款（资金已结算，指向冲正流程）")
    void 已完成拒绝退款() {
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(order(OrderStatus.COMPLETED.getCode()));

        assertThatThrownBy(() -> controller().refund("SWO-1", null))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("已完成订单不支持人工退款");
        verifyNoInteractions(refundService);
    }

    @Test
    @DisplayName("无可退金额拒绝（如仅押金流水的 TAKE 单）")
    void 无可退金额拒绝() {
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(order(OrderStatus.EXCEPTION.getCode()));
        when(refundService.refundableAmount(99L)).thenReturn(0);

        assertThatThrownBy(() -> controller().refund("SWO-1", null))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无可退金额");
        verify(refundService, never()).refund(anyLong(), anyLong(), anyInt(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("EXCEPTION 单正常退款（幂等 reason=ADMIN_MANUAL）")
    void 异常单可退款() {
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(order(OrderStatus.EXCEPTION.getCode()));
        when(refundService.refundableAmount(99L)).thenReturn(300);
        RefundRecordEntity record = new RefundRecordEntity();
        record.setRefundNo("RF1");
        record.setAmountFen(300);
        record.setReason("ADMIN_MANUAL");
        record.setStatus("WAIT");
        when(refundService.refund(99L, 7L, 300, "ADMIN_MANUAL")).thenReturn(record);

        var result = controller().refund("SWO-1", null);

        assertThat(result.getData().get("refundNo")).isEqualTo("RF1");
        verify(refundService).refund(99L, 7L, 300, "ADMIN_MANUAL");
    }
}
