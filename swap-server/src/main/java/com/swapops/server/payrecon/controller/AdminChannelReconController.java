package com.swapops.server.payrecon.controller;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.common.Result;
import com.swapops.server.payrecon.entity.ReconDiffEntity;
import com.swapops.server.payrecon.service.ChannelReconService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端渠道对账（S7 WP-C，@PreAuthorize 按 admin:recon:*）：
 * 日报 / 差异列表 / 账单导入（幂等覆盖+对账）/ 手动对账 / 差异处置。
 */
@RestController
@RequestMapping("admin/recon")
public class AdminChannelReconController {

    private final ChannelReconService channelReconService;

    public AdminChannelReconController(ChannelReconService channelReconService) {
        this.channelReconService = channelReconService;
    }

    @GetMapping("/report")
    @PreAuthorize("hasAuthority('" + AdminRole.RECON_READ + "')")
    public Result<Map<String, Object>> report(@RequestParam String date,
                                              @RequestParam(required = false) String channel) {
        return Result.ok(channelReconService.report(date, channel));
    }

    @GetMapping("/diff")
    @PreAuthorize("hasAuthority('" + AdminRole.RECON_READ + "')")
    public Result<List<ReconDiffEntity>> diffs(@RequestParam(required = false) String date,
                                               @RequestParam(required = false) String channel,
                                               @RequestParam(required = false) String status) {
        return Result.ok(channelReconService.listDiffs(date, channel, status));
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('" + AdminRole.RECON_IMPORT + "')")
    @AdminLog("RECON_IMPORT")
    public Result<ChannelReconService.ReconResult> importBill(@RequestParam String date,
                                                              @RequestParam(required = false) String channel,
                                                              @RequestBody CsvImportForm form) {
        return Result.ok(channelReconService.importBill(date, channel, form.getCsv()));
    }

    @PostMapping("/run")
    @PreAuthorize("hasAuthority('" + AdminRole.RECON_IMPORT + "')")
    @AdminLog("RECON_RUN")
    public Result<ChannelReconService.ReconResult> run(@RequestParam String date,
                                                       @RequestParam(required = false) String channel) {
        return Result.ok(channelReconService.reconcile(date, channel));
    }

    @PostMapping("/diff/{id}/handle")
    @PreAuthorize("hasAuthority('" + AdminRole.RECON_HANDLE + "')")
    @AdminLog("RECON_HANDLE")
    public Result<Map<String, Object>> handle(@PathVariable Long id,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String remark) {
        channelReconService.handle(id, status, remark);
        return Result.ok(Map.of("diffId", id, "handled", true));
    }

    /** 导入表单（CSV 文本经 JSON 传输） */
    public static class CsvImportForm {
        private String csv;

        public String getCsv() {
            return csv;
        }

        public void setCsv(String csv) {
            this.csv = csv;
        }
    }
}
