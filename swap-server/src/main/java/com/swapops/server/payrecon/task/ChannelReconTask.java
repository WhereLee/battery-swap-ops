package com.swapops.server.payrecon.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.server.alarm.service.TaskWatchdog;
import com.swapops.server.common.lock.JobLockService;
import com.swapops.server.payrecon.dao.ChannelBillDao;
import com.swapops.server.payrecon.entity.ChannelBillEntity;
import com.swapops.server.payrecon.service.ChannelReconService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 渠道对账每日任务（S7 WP-C，默认 02:40）：对账"昨日"账单。
 * 账单未导入 → 记日志跳过（等人工导入/渠道送达），看护 beat 始终打点（任务停摆可被看护发现）。
 */
@Slf4j
@Component
public class ChannelReconTask {

    private final ChannelReconService channelReconService;
    private final ChannelBillDao channelBillDao;
    private final JobLockService jobLockService;
    private final TaskWatchdog watchdog;

    public ChannelReconTask(ChannelReconService channelReconService, ChannelBillDao channelBillDao,
                            JobLockService jobLockService, TaskWatchdog watchdog) {
        this.channelReconService = channelReconService;
        this.channelBillDao = channelBillDao;
        this.jobLockService = jobLockService;
        this.watchdog = watchdog;
    }

    @Scheduled(cron = "${swap.recon.channel-cron:0 40 2 * * ?}")
    public void daily() {
        if (!jobLockService.runWithLock("channel-recon", this::doDaily)) {
            log.debug("渠道对账：另一实例执行中，跳过本轮");
        }
    }

    private void doDaily() {
        String yesterday = LocalDate.now().minusDays(1).toString();
        try {
            Long bills = channelBillDao.selectCount(new LambdaQueryWrapper<ChannelBillEntity>()
                    .eq(ChannelBillEntity::getBillDate, yesterday));
            if (bills == null || bills == 0) {
                log.warn("[渠道对账] 昨日账单未导入，跳过对账（人工导入后可用 /admin/recon/run 补跑）date={}", yesterday);
            } else {
                // S7 韧性补丁 G3：与人工导入/重建互斥（同锁名）
                boolean ran = jobLockService.runWithLock("channel-recon-io", () -> {
                    ChannelReconService.ReconResult result =
                            channelReconService.reconcile(yesterday, ChannelReconService.DEFAULT_CHANNEL);
                    log.info("[渠道对账] 每日对账完成 date={} bills={} platform={} diffs={}",
                            result.billDate(), result.billRows(), result.platformRows(), result.diffs());
                });
                if (!ran) {
                    log.warn("[渠道对账] IO 锁被占用（人工导入中），本轮跳过 date={}", yesterday);
                }
            }
        } catch (Exception e) {
            log.error("[渠道对账] 每日对账异常（下轮重试） date={} cause={}", yesterday, e.getMessage());
        } finally {
            watchdog.beat("channel-recon");
        }
    }
}
