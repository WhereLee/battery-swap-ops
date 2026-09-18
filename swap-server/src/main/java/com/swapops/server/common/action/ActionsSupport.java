package com.swapops.server.common.action;

import com.swapops.server.agent.enums.AgentActionStatus;
import com.swapops.server.transfer.enums.TransferStatus;
import com.swapops.server.workorder.enums.WorkOrderStatus;

import java.util.List;

/**
 * 能力位（S8）：状态 code → 当前可执行动作码 的纯函数。
 *
 * <p>动作码与管理端端点的动作段字面一致（如 {@code /admin/work-order/{id}/assign} → "assign"），
 * 前端据此渲染按钮，<b>不在前端复制状态判断</b>——两侧各写一套规则迟早漂移，
 * 漂移的表现就是"按钮能点但后端返回 400"。
 *
 * <p>纪律（与 {@code @DataFilter} 同类的架构纪律）：<b>状态机新增状态必须在此登记</b>，
 * 由 {@code ActionsSupportTest} 的覆盖性用例兜底（遍历每个枚举的全部 code）。
 * 跨资源的可用性（例如"该告警是否已有工单"）不在这里判断，由视图层按查询结果补充。
 */
public final class ActionsSupport {

    private ActionsSupport() {
    }

    /** 工单（S4.4）：OPEN→TRIAGED→ASSIGNED→HANDLING→VERIFIED→CLOSED，只前向。 */
    public static List<String> workOrder(Integer status) {
        WorkOrderState parsed = WorkOrderState.of(status);
        if (parsed == null) {
            return List.of();
        }
        return switch (parsed) {
            case OPEN -> List.of("triage");
            case TRIAGED -> List.of("assign");
            case ASSIGNED -> List.of("start");
            case HANDLING -> List.of("verify");
            case VERIFIED -> List.of("close");
            case CLOSED -> List.of();
        };
    }

    /**
     * Agent 建议单（S4.6）：只有 PROPOSED 可人工处置；EXECUTING 是执行中（重复确认已被 CAS 挡），
     * EXECUTED/REJECTED/FAILED 为终态（失败需重新建议，不重放）。
     */
    public static List<String> agentAction(Integer status) {
        if (status == null) {
            return List.of();
        }
        for (AgentActionStatus value : AgentActionStatus.values()) {
            if (value.getCode() == status) {
                return value == AgentActionStatus.PROPOSED ? List.of("confirm", "reject") : List.of();
            }
        }
        return List.of();
    }

    /**
     * 调拨任务（S4.2）：出库允许 APPROVED/EXECUTING（多明细并存，部分已出部分待出），
     * 入库要求 EXECUTING；EXECUTING 不可取消（必须闭环，见 TransferService#cancel）。
     */
    public static List<String> transfer(Integer status) {
        if (status == null) {
            return List.of();
        }
        for (TransferStatus value : TransferStatus.values()) {
            if (value.getCode() != status) {
                continue;
            }
            return switch (value) {
                case DRAFT -> List.of("approve", "cancel");
                case APPROVED -> List.of("cancel", "out");
                case EXECUTING -> List.of("out", "in");
                case DONE, CANCELLED -> List.of();
            };
        }
        return List.of();
    }

    /** 告警（S3.6）：未处理可人工处置；自动恢复/已处理为终态（无动作）。 */
    public static List<String> alarm(Integer handled) {
        return handled != null && handled == 0 ? List.of("handle") : List.of();
    }

    /**
     * 工单状态镜像枚举。
     *
     * <p>刻意不直接 switch {@link WorkOrderStatus}：其 {@code fromCode} 对未知值抛异常，
     * 而能力位的语义是"未知/为空一律无可执行动作"（不得因脏数据让页面崩）。
     * 编译器要求 switch 覆盖全部枚举值——<b>新增状态未登记时编译失败</b>，纪律由编译器保证。
     */
    private enum WorkOrderState {
        OPEN, TRIAGED, ASSIGNED, HANDLING, VERIFIED, CLOSED;

        static WorkOrderState of(Integer code) {
            if (code == null) {
                return null;
            }
            for (WorkOrderStatus value : WorkOrderStatus.values()) {
                if (value.getCode() == code) {
                    return valueOf(value.name());
                }
            }
            return null;
        }
    }
}
