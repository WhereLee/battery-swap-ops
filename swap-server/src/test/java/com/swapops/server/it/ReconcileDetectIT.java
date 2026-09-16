package com.swapops.server.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.contract.CellStatus;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.reconcile.ReconcileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-2 日终对账检出（MySQL 容器）：十四组不变量在干净夹具上零差异；
 * 注入一条"仓引用不存在电池"的断链 → 精确命中 cell-battery-consistency 组（样本可定位）；
 * 恢复后回到零差异（对账是只读核查，测后不留残影）。
 */
@DisplayName("IT-2 日终对账检出")
class ReconcileDetectIT extends AbstractContainersIT {

    private static final String CABINET = "SWAP-C-003";

    @Autowired
    private ReconcileService reconcileService;
    @Autowired
    private CabinetDao cabinetDao;
    @Autowired
    private CellDao cellDao;

    private ReconcileService.CheckResult check(ReconcileService.ReconcileReport report, String name) {
        return report.checks().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("对账组缺失: " + name));
    }

    @Test
    @DisplayName("注入断链被命中并恢复零差异")
    void detectsInjectedInconsistencyAndRecovers() {
        // 1) 干净基线：十四组不变量
        ReconcileService.ReconcileReport clean = reconcileService.run();
        assertThat(clean.checks()).as("十四组不变量").hasSize(14);
        int baseConsistency = check(clean, "cell-battery-consistency").violations();
        int baseTotal = clean.totalViolations();

        // 2) 注入：C-003 取一个空仓，引用不存在的电池（cell → battery 断链）
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, CABINET));
        assertThat(cabinet).isNotNull();
        CellEntity victim = cellDao.selectOne(new LambdaQueryWrapper<CellEntity>()
                .eq(CellEntity::getCabinetId, cabinet.getId())
                .eq(CellEntity::getStatus, CellStatus.EMPTY.getCode())
                .orderByAsc(CellEntity::getId)
                .last("LIMIT 1"));
        assertThat(victim).as("存在可控空仓").isNotNull();

        cellDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CellEntity>()
                .eq(CellEntity::getId, victim.getId())
                .set(CellEntity::getBatteryId, 999999999L)
                .set(CellEntity::getStatus, CellStatus.OCCUPIED.getCode())
                .set(CellEntity::getUpdateTime, System.currentTimeMillis()));
        try {
            ReconcileService.ReconcileReport dirty = reconcileService.run();
            assertThat(dirty.checks()).hasSize(14);
            ReconcileService.CheckResult hit = check(dirty, "cell-battery-consistency");
            assertThat(hit.violations()).as("精确命中 1 条").isEqualTo(baseConsistency + 1);
            assertThat(hit.samples()).anyMatch(s -> s.contains("cell→battery"));
            assertThat(dirty.totalViolations()).isEqualTo(baseTotal + 1);
        } finally {
            cellDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CellEntity>()
                    .eq(CellEntity::getId, victim.getId())
                    .set(CellEntity::getBatteryId, null)
                    .set(CellEntity::getStatus, CellStatus.EMPTY.getCode())
                    .set(CellEntity::getUpdateTime, System.currentTimeMillis()));
        }

        // 3) 恢复：回到基线（测后无残影）
        ReconcileService.ReconcileReport restored = reconcileService.run();
        assertThat(check(restored, "cell-battery-consistency").violations()).isEqualTo(baseConsistency);
        assertThat(restored.totalViolations()).isEqualTo(baseTotal);
    }
}
