package com.swapops.server.payrecon.controller;

import com.swapops.server.common.RRException;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.payrecon.service.ChannelReconService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 渠道对账控制器单测（S7 韧性补丁 G3）：导入/重建在 IO 互斥锁内执行；锁被占拒绝。
 */
@DisplayName("渠道对账接口（IO 互斥）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminChannelReconControllerTest {

    @Mock
    private ChannelReconService channelReconService;
    @Mock
    private JobLockService jobLockService;

    private AdminChannelReconController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminChannelReconController(channelReconService, jobLockService);
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });
    }

    private AdminChannelReconController.CsvImportForm form(String csv) {
        AdminChannelReconController.CsvImportForm form = new AdminChannelReconController.CsvImportForm();
        form.setCsv(csv);
        return form;
    }

    @Test
    @DisplayName("导入：在锁内委托服务（csv 透传）")
    void 导入走锁() {
        when(channelReconService.importBill(eq("2026-09-15"), eq("MOCK"), anyString()))
                .thenReturn(new ChannelReconService.ReconResult("2026-09-15", "MOCK", 1, 1, 0));

        controller.importBill("2026-09-15", "MOCK", form("bill_date,trade_no,amount_fen,status"));

        verify(jobLockService).runWithLock(eq("channel-recon-io"), any(Runnable.class));
        verify(channelReconService).importBill(eq("2026-09-15"), eq("MOCK"), anyString());
    }

    @Test
    @DisplayName("锁被占：拒绝并提示稍后重试")
    void 锁被占拒绝() {
        when(jobLockService.runWithLock(anyString(), any(Runnable.class))).thenReturn(false);

        assertThatThrownBy(() -> controller.run("2026-09-15", "MOCK"))
                .isInstanceOf(RRException.class).hasMessageContaining("稍后重试");
    }
}
