package com.swapops.server.admin.data;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.swapops.server.admin.security.AdminContext;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.asset.entity.StationEntity;
import com.swapops.server.common.RRException;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据范围注入工具（P1-8）：管理端查询构建处调用，按当前身份的站点范围追加过滤条件。
 *
 * <p>语义（fail-closed）：
 * <ul>
 *   <li>无管理上下文（用户端/调度线程/单测）/ ALL 范围 / bootstrap → 不受限（不追加）；</li>
 *   <li>STATION 范围 → 追加 {@code in(列, 站点id集合)}；集合为空 → {@code eq(列, -1)} 恒假（看不到任何数据）；</li>
 *   <li>资源级校验（详情类）→ {@link #requireStationAccess}：越域抛 403。</li>
 * </ul>
 * 所有 apply 调用都会向 {@link DataScopeGuard} 标记"已应用"（配合 @DataFilter 自检）。</p>
 */
@Slf4j
public final class DataScopeSupport {

    private DataScopeSupport() {
    }

    /**
     * 当前身份的可见站点范围。
     *
     * @return null=不受限（不追加过滤）；非 null=受限（可能空集=无可见数据）
     */
    public static Set<Long> stationIdsOrNull() {
        AdminContext.Principal principal = AdminContext.current();
        if (principal == null || !principal.dataScoped()) {
            return null;
        }
        return principal.scopeStationIds() == null ? Set.of() : principal.scopeStationIds();
    }

    /** 列表过滤：按"站点 id 列"追加范围（适用于 station.id / cabinet.station_id / swap_order.station_id）。 */
    public static <T> void applyStation(LambdaQueryWrapper<T> wrapper, SFunction<T, ?> stationColumn) {
        Set<Long> stationIds = stationIdsOrNull();
        if (stationIds == null) {
            return;
        }
        DataScopeGuard.markApplied();
        if (stationIds.isEmpty()) {
            wrapper.eq(stationColumn, -1L); // 无可见站点：恒假条件（不暴露任何行）
            return;
        }
        wrapper.in(stationColumn, stationIds);
    }

    /**
     * 列表过滤的"经柜"两级解析：受限时先解出可见柜 id 集（station_id ∈ 范围）。
     *
     * @return null=不受限；非 null=受限（可能空集）
     */
    public static Set<Long> cabinetIdsOrNull(CabinetDao cabinetDao) {
        Set<Long> stationIds = stationIdsOrNull();
        if (stationIds == null) {
            return null;
        }
        if (stationIds.isEmpty()) {
            return Set.of();
        }
        return cabinetDao.selectList(new LambdaQueryWrapper<CabinetEntity>()
                        .in(CabinetEntity::getStationId, stationIds))
                .stream().map(CabinetEntity::getId).collect(Collectors.toSet());
    }

    /**
     * 列表过滤的"经柜→经仓"两级解析：站点范围 → 柜 id 集 → 仓 id 集
     * （battery.cell_id 维度用）。
     *
     * @return null=不受限；非 null=受限（可能空集）
     */
    public static Set<Long> cellIdsOrNull(CabinetDao cabinetDao, CellDao cellDao) {
        Set<Long> cabinetIds = cabinetIdsOrNull(cabinetDao);
        if (cabinetIds == null) {
            return null;
        }
        if (cabinetIds.isEmpty()) {
            return Set.of();
        }
        return cellDao.selectList(new LambdaQueryWrapper<CellEntity>()
                        .in(CellEntity::getCabinetId, cabinetIds))
                .stream().map(CellEntity::getId).collect(Collectors.toSet());
    }

    /** 按 id 集合追加过滤（cell.cabinet_id / battery.cell_id 等经柜/经仓维度）；null=不受限。 */
    public static <T> void applyIds(LambdaQueryWrapper<T> wrapper, SFunction<T, ?> column, Set<Long> ids) {
        if (ids == null) {
            return;
        }
        DataScopeGuard.markApplied();
        if (ids.isEmpty()) {
            wrapper.eq(column, -1L);
            return;
        }
        wrapper.in(column, ids);
    }

    /**
     * 无站点归属的资源操作（新建站点/登记在途电池等）：受限身份一律拒绝（fail-closed——
     * 目标不在任何既有授权范围内，无法判定归属）。
     */
    public static void requireUnrestricted(String action) {
        if (stationIdsOrNull() != null) {
            DataScopeGuard.markApplied();
            throw new RRException(403, "数据范围受限身份不能执行该操作（目标无站点归属）: " + action);
        }
    }

    /**
     * 资源级校验（详情/操作入口）：站点范围身份访问非本域资源 → 403。
     * stationId 为 null（无站点资源）对受限身份一律拒绝。
     */
    public static void requireStationAccess(Long stationId) {
        Set<Long> stationIds = stationIdsOrNull();
        if (stationIds == null) {
            return;
        }
        DataScopeGuard.markApplied();
        if (stationId == null || !stationIds.contains(stationId)) {
            throw new RRException(403, "无权访问该站点数据（数据范围受限）");
        }
    }

    /** 站点范围身份解析 stationNo → id 集合（AdminAuthFilter 登录期调用；解析失败 fail-closed 空集）。 */
    public static Set<Long> resolveScopeStationIds(StationDao stationDao, List<String> stationNos) {
        if (stationNos == null || stationNos.isEmpty()) {
            return Set.of();
        }
        try {
            return stationDao.selectList(new LambdaQueryWrapper<StationEntity>()
                            .in(StationEntity::getStationNo, stationNos))
                    .stream().map(StationEntity::getId).collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException e) {
            // fail-closed：解析失败视为无可见数据（宁可少不可越权）
            log.error("[data-scope] 站点范围解析失败（fail-closed 空集） nos={} cause={}", stationNos, e.getMessage());
            return Set.of();
        }
    }
}
