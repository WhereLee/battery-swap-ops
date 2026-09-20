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

    private void stationScoped(Long... stationIds) {
        com.swapops.server.admin.security.AdminContext.set(
                new com.swapops.server.admin.security.AdminContext.Principal(9L, "fin01",
                        com.swapops.server.admin.enums.AdminRole.FINANCE, false,
                        "STATION", java.util.Set.of(stationIds)));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        com.swapops.server.admin.security.AdminContext.set(null);
    }

    @Test
    @DisplayName("批次43 补（F-02）：站点范围身份不得对域外订单放款——读路径有范围，写路径此前漏了")
    void 域外订单退款被拒() {
        stationScoped(7L);
        SwapOrderEntity other = order(OrderStatus.EXCEPTION.getCode());
        other.setStationId(99L); // 域外站点
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(other);

        assertThatThrownBy(() -> controller().refund("SWO-1", 100))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无权访问该站点数据");
        verifyNoInteractions(refundService);
    }

    @Test
    @DisplayName("批次43 补（F-02）：冲正同样按订单站点校验（它会写负向分账行）")
    void 域外订单冲正被拒() {
        stationScoped(7L);
        SwapOrderEntity other = order(OrderStatus.COMPLETED.getCode());
        other.setStationId(99L);
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(other);

        assertThatThrownBy(() -> controller().reversal("SWO-1", 100))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("无权访问该站点数据");
        verifyNoInteractions(refundService);
    }

    @Test
    @DisplayName("批次43 补（F-02）：本域订单照常放行（范围校验不误伤）")
    void 本域订单放行() {
        stationScoped(7L);
        SwapOrderEntity mine = order(OrderStatus.EXCEPTION.getCode());
        mine.setStationId(7L);
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(mine);
        RefundRecordEntity record = new RefundRecordEntity();
        record.setRefundNo("RF9");
        record.setAmountFen(100);
        record.setReason("ADMIN_MANUAL");
        record.setStatus("SUCCESS");
        when(refundService.refund(99L, 7L, 100, "ADMIN_MANUAL")).thenReturn(record);

        assertThat(controller().refund("SWO-1", 100).getData()).containsEntry("refundNo", "RF9");
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

    @Test
    @DisplayName("冲正退款（S7 WP-B）：COMPLETED 单可走 reversal（reason=ADMIN_REVERSAL）")
    void 冲正退款() {
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(order(OrderStatus.COMPLETED.getCode()));
        when(refundService.refundableAmount(99L)).thenReturn(300);
        RefundRecordEntity record = new RefundRecordEntity();
        record.setRefundNo("RF9");
        record.setAmountFen(300);
        record.setReason("ADMIN_REVERSAL");
        record.setStatus("SUCCESS");
        when(refundService.refund(99L, 7L, 300, "ADMIN_REVERSAL")).thenReturn(record);

        var result = controller().reversal("SWO-1", null);

        assertThat(result.getData().get("refundNo")).isEqualTo("RF9");
        verify(refundService).refund(99L, 7L, 300, "ADMIN_REVERSAL");
    }

    @Test
    @DisplayName("冲正退款：非完成单拒绝")
    void 冲正仅完成单() {
        when(swapOrderService.findByOrderNo("SWO-1")).thenReturn(order(OrderStatus.EXCEPTION.getCode()));

        assertThatThrownBy(() -> controller().reversal("SWO-1", 100))
                .isInstanceOf(RRException.class).hasMessageContaining("仅适用于已完成订单");
        verifyNoInteractions(refundService);
    }
}
