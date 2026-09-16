package com.swapops.server.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.server.common.RRException;
import com.swapops.server.device.config.SwapRedisKeys;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.order.service.AllocationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-3 并发零超卖（真实 Redis Lua + DB 条件更新兜底）：
 * 20 并发抢 6 个满电仓——恰好 6 个成功、14 个被拒、无重复授予；
 * 事后一致性：DB 恰好 6 仓锁且锁归属互异，Redis 可分配集合耗尽、预占快照逐一在位。
 */
@DisplayName("IT-3 并发零超卖")
class AllocationOversellIT extends AbstractContainersIT {

    private static final String CABINET = "SWAP-C-002";
    private static final int CONCURRENCY = 20;
    private static final long ORDER_ID_BASE = 99000L;

    @Autowired
    private AllocationService allocationService;
    @Autowired
    private CabinetDao cabinetDao;
    @Autowired
    private CellDao cellDao;
    @Autowired
    private StringRedisTemplate redis;

    /** 一次成功授予：订单号 → 仓 id */
    private record Grant(String orderNo, Long cellId) {
    }

    @Test
    @DisplayName("20 并发抢 6 仓：零超卖 + 台账/Redis 一致")
    void concurrentAllocationNeverOversells() throws Exception {
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, CABINET));
        assertThat(cabinet).isNotNull();
        int stock = (int) allocationService.countAvailable(CABINET, true);
        assertThat(stock).as("种子满电仓数（前 fullCells=6 仓）").isEqualTo(6);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Grant>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    AllocationService.AllocResult result = allocationService.allocate(
                            CABINET, ORDER_ID_BASE + idx, "IT3-" + idx, true);
                    return new Grant("IT3-" + idx, result.cell().getId());
                } catch (RRException e) {
                    return null;
                }
            }));
        }
        go.countDown();

        List<Grant> granted = new ArrayList<>();
        int rejected = 0;
        for (Future<Grant> future : futures) {
            Grant grant = future.get(30, TimeUnit.SECONDS);
            if (grant == null) {
                rejected++;
            } else {
                granted.add(grant);
            }
        }
        pool.shutdownNow();

        // P0-1 门禁验收探针（临时，验证"IT 失败能红 CI"后即回滚，勿保留）
        assertThat(true).as("CI-RED-PROBE 故意失败：验证 IT 失败能让 CI 变红").isFalse();

        // 1) 恰好库存数成功、无重复授予、其余全部被拒
        assertThat(granted).as("零超卖：成功数 == 库存").hasSize(stock);
        assertThat(granted).extracting(Grant::cellId).doesNotHaveDuplicates();
        assertThat(rejected).isEqualTo(CONCURRENCY - stock);

        // 2) DB 兜底：该柜恰 6 仓持锁，且锁归属（orderId）互异并来自本次并发请求
        List<CellEntity> locked = cellDao.selectList(new LambdaQueryWrapper<CellEntity>()
                .eq(CellEntity::getCabinetId, cabinet.getId())
                .isNotNull(CellEntity::getLockOrderId));
        assertThat(locked).hasSize(stock);
        assertThat(locked).extracting(CellEntity::getLockOrderId).doesNotHaveDuplicates();
        assertThat(locked).allSatisfy(cell ->
                assertThat(cell.getLockOrderId()).isBetween(ORDER_ID_BASE, ORDER_ID_BASE + CONCURRENCY - 1));

        // 3) Redis 侧一致：可分配集合耗尽 + 每个成功单的预占快照在位且指向授予仓
        assertThat(allocationService.countAvailable(CABINET, true)).isZero();
        for (Grant grant : granted) {
            assertThat(redis.opsForValue().get(SwapRedisKeys.PREEMPT_PREFIX + grant.orderNo()))
                    .isEqualTo(String.valueOf(grant.cellId()));
            assertThat(redis.hasKey(SwapRedisKeys.CELL_LOCK_PREFIX + grant.cellId())).isTrue();
        }
    }
}
