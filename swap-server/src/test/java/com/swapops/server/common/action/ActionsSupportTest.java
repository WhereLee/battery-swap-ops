package com.swapops.server.common.action;

import com.swapops.server.agent.enums.AgentActionStatus;
import com.swapops.server.transfer.enums.TransferStatus;
import com.swapops.server.workorder.enums.WorkOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 能力位测试：主链映射逐态核对 + 覆盖性（每个状态必须被显式映射）+ 脏值不抛。 */
@DisplayName("能力位（ActionsSupport）")
class ActionsSupportTest {

    @Test
    @DisplayName("工单：主链每态只有一个前进动作，与端点动作段同名")
    void 工单主链映射() {
        assertThat(ActionsSupport.workOrder(WorkOrderStatus.OPEN.getCode())).containsExactly("triage");
        assertThat(ActionsSupport.workOrder(WorkOrderStatus.TRIAGED.getCode())).containsExactly("assign");
        assertThat(ActionsSupport.workOrder(WorkOrderStatus.ASSIGNED.getCode())).containsExactly("start");
        assertThat(ActionsSupport.workOrder(WorkOrderStatus.HANDLING.getCode())).containsExactly("verify");
        assertThat(ActionsSupport.workOrder(WorkOrderStatus.VERIFIED.getCode())).containsExactly("close");
        assertThat(ActionsSupport.workOrder(WorkOrderStatus.CLOSED.getCode())).isEmpty();
    }

    @Test
    @DisplayName("工单覆盖性：全部状态都被映射（非终态必有动作，杜绝新增态漏配）")
    void 工单覆盖性() {
        for (WorkOrderStatus status : WorkOrderStatus.values()) {
            List<String> actions = ActionsSupport.workOrder(status.getCode());
            if (status == WorkOrderStatus.CLOSED) {
                assertThat(actions).as("终态 %s 不应有动作", status).isEmpty();
            } else {
                assertThat(actions).as("状态 %s 必须登记可执行动作", status).isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName("建议单：仅 PROPOSED 可 confirm/reject，执行中与终态无动作")
    void 建议单映射() {
        assertThat(ActionsSupport.agentAction(AgentActionStatus.PROPOSED.getCode()))
                .containsExactly("confirm", "reject");
        for (AgentActionStatus status : AgentActionStatus.values()) {
            if (status == AgentActionStatus.PROPOSED) {
                continue;
            }
            assertThat(ActionsSupport.agentAction(status.getCode()))
                    .as("%s 不应有可执行动作", status).isEmpty();
        }
    }

    @Test
    @DisplayName("调拨：DRAFT 可审批/取消，EXECUTING 可出入库但不可取消（必须闭环）")
    void 调拨映射() {
        assertThat(ActionsSupport.transfer(TransferStatus.DRAFT.getCode()))
                .containsExactly("approve", "cancel");
        assertThat(ActionsSupport.transfer(TransferStatus.APPROVED.getCode()))
                .containsExactly("cancel", "out");
        assertThat(ActionsSupport.transfer(TransferStatus.EXECUTING.getCode()))
                .containsExactly("out", "in");
        assertThat(ActionsSupport.transfer(TransferStatus.DONE.getCode())).isEmpty();
        assertThat(ActionsSupport.transfer(TransferStatus.CANCELLED.getCode())).isEmpty();
    }

    @Test
    @DisplayName("告警：未处理可人工处置，已处理/自动恢复无动作")
    void 告警映射() {
        assertThat(ActionsSupport.alarm(0)).containsExactly("handle");
        assertThat(ActionsSupport.alarm(1)).isEmpty();
        assertThat(ActionsSupport.alarm(null)).isEmpty();
    }

    @Test
    @DisplayName("脏值：null / 未知 code 一律返回空且不抛（脏数据不得让页面崩）")
    void 脏值安全() {
        for (int code : new int[]{-1, 0, 999}) {
            assertThatCode(() -> {
                ActionsSupport.workOrder(code);
                ActionsSupport.agentAction(code);
                ActionsSupport.transfer(code);
            }).doesNotThrowAnyException();
            assertThat(ActionsSupport.workOrder(code)).isEmpty();
            assertThat(ActionsSupport.agentAction(code)).isEmpty();
            assertThat(ActionsSupport.transfer(code)).isEmpty();
        }
    }
}
