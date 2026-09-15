package com.swapops.server.payrecon.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.common.RRException;
import com.swapops.server.order.dao.PayOrderDao;
import com.swapops.server.order.entity.PayOrderEntity;
import com.swapops.server.payrecon.dao.ChannelBillDao;
import com.swapops.server.payrecon.dao.ReconDiffDao;
import com.swapops.server.payrecon.entity.ChannelBillEntity;
import com.swapops.server.payrecon.entity.ReconDiffEntity;
import com.swapops.server.payrecon.enums.ReconDiffStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 渠道对账单测（S7 WP-C）：导出差异注入 / 解析校验 / 四类差异 / 重建保留处置留痕 / 处置 CAS。
 */
@DisplayName("渠道对账（T+1）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChannelReconServiceTest {

    @Mock
    private ChannelBillDao channelBillDao;
    @Mock
    private ReconDiffDao reconDiffDao;
    @Mock
    private PayOrderDao payOrderDao;
    @Mock
    private AlarmService alarmService;

    private ChannelReconService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ChannelBillEntity.class);
        TableInfoHelper.initTableInfo(assistant, ReconDiffEntity.class);
        TableInfoHelper.initTableInfo(assistant, PayOrderEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new ChannelReconService(channelBillDao, reconDiffDao, payOrderDao, alarmService);
    }

    private PayOrderEntity order(String tradeNo, int amount, String status) {
        PayOrderEntity order = new PayOrderEntity();
        order.setTradeNo(tradeNo);
        order.setAmountFen(amount);
        order.setStatus(status);
        order.setChannel("MOCK");
        order.setCallbackTime(System.currentTimeMillis());
        return order;
    }

    private ChannelBillEntity bill(String tradeNo, int amount, String status) {
        ChannelBillEntity bill = new ChannelBillEntity();
        bill.setBillDate("2026-09-14");
        bill.setChannel("MOCK");
        bill.setTradeNo(tradeNo);
        bill.setAmountFen(amount);
        bill.setStatus(status);
        return bill;
    }

    @Test
    @DisplayName("导出：基础行 + 四类注入（missing/extra/amount/status）")
    void 导出与注入() {
        when(payOrderDao.selectList(any())).thenReturn(List.of(
                order("R1", 1000, "SUCCESS"), order("R2", 2000, "SUCCESS")));

        String plain = service.exportBillCsv("2026-09-14", null);
        assertThat(plain).contains("bill_date,trade_no,amount_fen,status");
        assertThat(plain).contains("R1,1000,SUCCESS").contains("R2,2000,SUCCESS");

        String missing = service.exportBillCsv("2026-09-14", "missing");
        assertThat(missing).doesNotContain("R1,").doesNotContain("R2,");

        String amount = service.exportBillCsv("2026-09-14", "amount");
        assertThat(amount).contains("R1,1100,SUCCESS");

        String status = service.exportBillCsv("2026-09-14", "status");
        assertThat(status).contains("R1,1000,CLOSED");

        String extra = service.exportBillCsv("2026-09-14", "extra");
        assertThat(extra).contains("RFAKE");
    }

    @Test
    @DisplayName("解析校验：空内容/列数错/金额错/状态错均拒绝")
    void 解析校验() {
        assertThatThrownBy(() -> service.parseCsv(""))
                .isInstanceOf(RRException.class).hasMessageContaining("为空");
        assertThatThrownBy(() -> service.parseCsv("bill_date,trade_no\n2026-09-14,R1"))
                .isInstanceOf(RRException.class).hasMessageContaining("4 列");
        assertThatThrownBy(() -> service.importBill("2026-09-14", "MOCK",
                "bill_date,trade_no,amount_fen,status\n2026-09-14,R1,xx,SUCCESS"))
                .isInstanceOf(RRException.class).hasMessageContaining("金额非法");
        assertThatThrownBy(() -> service.importBill("2026-09-14", "MOCK",
                "bill_date,trade_no,amount_fen,status\n2026-09-14,R1,100,FAIL"))
                .isInstanceOf(RRException.class).hasMessageContaining("状态非法");
        assertThatThrownBy(() -> service.importBill("2026-09-14", "MOCK",
                "bill_date,trade_no,amount_fen,status\n2026-09-13,R1,100,SUCCESS"))
                .isInstanceOf(RRException.class).hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("对账：四类差异计算 + 告警")
    void 四类差异() {
        when(channelBillDao.selectList(any())).thenReturn(List.of(
                bill("R-NO-PLATFORM", 1000, "SUCCESS"),          // CHANNEL_ONLY
                bill("R-TWICE", 1500, "CLOSED"),                 // AMOUNT_MISMATCH? no -> covered below
                bill("R-STATUS", 3000, "SUCCESS")));             // STATUS_MISMATCH
        when(payOrderDao.selectList(any())).thenReturn(List.of(
                order("R-TWICE", 1500, "SUCCESS"),
                order("R-STATUS", 3000, "CLOSED"),
                order("R-NO-CHANNEL", 500, "SUCCESS")));         // PLATFORM_ONLY
        when(reconDiffDao.selectList(any())).thenReturn(List.of());

        ChannelReconService.ReconResult result = service.reconcile("2026-09-14", "MOCK");

        assertThat(result.diffs()).isEqualTo(4); // CHANNEL_ONLY + STATUS_MISMATCH×2 + PLATFORM_ONLY
        ArgumentCaptor<ReconDiffEntity> captor = ArgumentCaptor.forClass(ReconDiffEntity.class);
        verify(reconDiffDao, atLeastOnce()).insert(captor.capture());
        List<String> types = captor.getAllValues().stream().map(ReconDiffEntity::getDiffType).toList();
        assertThat(types).contains("CHANNEL_ONLY", "PLATFORM_ONLY", "STATUS_MISMATCH");
        verify(alarmService).raise(eq(AlarmService.DEVICE_SYSTEM), eq("channel-recon"),
                eq(AlarmType.CHANNEL_RECON_DIFF), anyString());
    }

    @Test
    @DisplayName("对账：金额不符单列一类；零差异自动关告警")
    void 金额差异与零差异() {
        when(channelBillDao.selectList(any())).thenReturn(List.of(bill("R1", 1100, "SUCCESS")));
        when(payOrderDao.selectList(any())).thenReturn(List.of(order("R1", 1000, "SUCCESS")));
        when(reconDiffDao.selectList(any())).thenReturn(List.of());

        ChannelReconService.ReconResult result = service.reconcile("2026-09-14", "MOCK");

        assertThat(result.diffs()).isEqualTo(1);
        ArgumentCaptor<ReconDiffEntity> captor = ArgumentCaptor.forClass(ReconDiffEntity.class);
        verify(reconDiffDao).insert(captor.capture());
        assertThat(captor.getValue().getDiffType()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(captor.getValue().getDetail()).contains("渠道=1100").contains("平台=1000");

        when(channelBillDao.selectList(any())).thenReturn(List.of(bill("R1", 1000, "SUCCESS")));
        when(reconDiffDao.selectList(any())).thenReturn(List.of());
        service.reconcile("2026-09-14", "MOCK");
        verify(alarmService).markRecovered(AlarmService.DEVICE_SYSTEM, "channel-recon",
                AlarmType.CHANNEL_RECON_DIFF);
    }

    @Test
    @DisplayName("重建：OPEN 清空重建，HANDLED 留痕不重建")
    void 重建保留处置留痕() {
        when(channelBillDao.selectList(any())).thenReturn(List.of(bill("R-HANDLED", 1100, "SUCCESS")));
        when(payOrderDao.selectList(any())).thenReturn(List.of(order("R-HANDLED", 1000, "SUCCESS")));
        ReconDiffEntity handled = new ReconDiffEntity();
        handled.setTradeNo("R-HANDLED");
        handled.setDiffType("AMOUNT_MISMATCH");
        handled.setStatus(ReconDiffStatus.HANDLED.name());
        when(reconDiffDao.selectList(any())).thenReturn(List.of(handled));

        ChannelReconService.ReconResult result = service.reconcile("2026-09-14", "MOCK");

        assertThat(result.diffs()).isZero();
        verify(reconDiffDao, never()).insert(any(ReconDiffEntity.class));
        verify(reconDiffDao).delete(any());
    }

    @Test
    @DisplayName("导入：先清后插（幂等覆盖）+ 落到对账")
    void 导入幂等覆盖() {
        when(channelBillDao.selectList(any())).thenReturn(List.of());
        when(payOrderDao.selectList(any())).thenReturn(List.of());

        ChannelReconService.ReconResult result = service.importBill("2026-09-14", "MOCK",
                "bill_date,trade_no,amount_fen,status\n2026-09-14,R1,1000,SUCCESS\n2026-09-14,R2,2000,CLOSED");

        assertThat(result.billRows()).isEqualTo(0); // selectList 桩返回空（对账读取走桩）
        verify(channelBillDao).delete(any());
        verify(channelBillDao, atLeastOnce()).insert(any(ChannelBillEntity.class));
        verify(alarmService, atLeastOnce()).markRecovered(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("处置：CAS OPEN→HANDLED；不存在/已处置拒绝")
    void 处置CAS() {
        when(reconDiffDao.update(isNull(), any())).thenReturn(1);
        assertThat(service.handle(9L, "HANDLED", "核对渠道后台一致")).isTrue();

        when(reconDiffDao.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.handle(9L, "HANDLED", null))
                .isInstanceOf(RRException.class).hasMessageContaining("已被处置");

        assertThatThrownBy(() -> service.handle(9L, "BAD", null))
                .isInstanceOf(RRException.class).hasMessageContaining("处置状态可选");
    }

    @Test
    @DisplayName("报表：按类型与状态聚合")
    void 报表聚合() {
        when(channelBillDao.selectCount(any())).thenReturn(2L);
        when(payOrderDao.selectList(any())).thenReturn(List.of(order("R1", 1000, "SUCCESS")));
        ReconDiffEntity open = new ReconDiffEntity();
        open.setDiffType("CHANNEL_ONLY");
        open.setStatus("OPEN");
        ReconDiffEntity handled = new ReconDiffEntity();
        handled.setDiffType("STATUS_MISMATCH");
        handled.setStatus("HANDLED");
        when(reconDiffDao.selectList(any())).thenReturn(List.of(open, handled));

        var report = service.report("2026-09-14", "MOCK");

        assertThat(report.get("bills")).isEqualTo(2L);
        @SuppressWarnings("unchecked")
        var byType = (java.util.Map<String, Integer>) report.get("byType");
        assertThat(byType.get("CHANNEL_ONLY")).isEqualTo(1);
        assertThat(report.get("openDiffs")).isEqualTo(1);
    }
}
