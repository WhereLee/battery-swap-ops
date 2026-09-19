package com.swapops.server.web.view.service;

import com.swapops.server.common.RRException;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.settlement.dao.AgentDao;
import com.swapops.server.settlement.dao.OrderSettlementDao;
import com.swapops.server.settlement.entity.AgentEntity;
import com.swapops.server.settlement.entity.OrderSettlementEntity;
import com.swapops.server.settlement.entity.SettlementStatementEntity;
import com.swapops.server.settlement.enums.SettlementStatus;
import com.swapops.server.settlement.service.SettlementService;
import com.swapops.server.web.view.AdminViews;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 资金口视图测试：结算单映射 + 能力位 + 多态代理归属 + 不存在时的 400 语义。 */
@DisplayName("资金口视图（结算单列表/详情）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminFinanceViewServiceTest {

    @Mock
    private SettlementService settlementService;
    @Mock
    private OrderSettlementDao orderSettlementDao;
    @Mock
    private AgentDao agentDao;

    private AdminFinanceViewService service;

    @BeforeEach
    void setUp() {
        service = new AdminFinanceViewService(settlementService, orderSettlementDao, agentDao);
    }

    private SettlementStatementEntity statement(int status, Long agentId) {
        SettlementStatementEntity entity = new SettlementStatementEntity();
        entity.setId(5L);
        entity.setStatementNo("ST1001");
        entity.setAgentId(agentId);
        entity.setPeriodStart(1700000000000L);
        entity.setPeriodEnd(1700086400000L);
        entity.setOrderCount(3);
        entity.setBaseAmountFen(900);
        entity.setAgentAmountFen(270);
        entity.setPlatformAmountFen(630);
        entity.setSubsidyFen(100);
        entity.setStatus(status);
        entity.setGeneratedBy("admin");
        entity.setCreateTime(1700000000000L);
        return entity;
    }

    @Test
    @DisplayName("结算单列表：金额原样透出、代理名批量解析、GENERATED 给 confirm")
    void 列表映射与能力位() {
        when(settlementService.pageStatements(1, 20, null, null))
                .thenReturn(PageResult.of(List.of(statement(SettlementStatus.GENERATED.getCode(), 7L)), 1, 1, 20));
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        agent.setName("华东代理");
        when(agentDao.selectBatchIds(any())).thenReturn(List.of(agent));

        AdminViews.SettlementVO vo = service.settlementPage(1, 20, null, null).getList().get(0);

        assertThat(vo.statementNo()).isEqualTo("ST1001");
        assertThat(vo.agentName()).isEqualTo("华东代理");
        // 分账守恒：代理 + 平台 = 基数（视图层不重算，只透出权威聚合结果）
        assertThat(vo.agentAmountFen() + vo.platformAmountFen()).isEqualTo(vo.baseAmountFen());
        assertThat(vo.allowedActions()).containsExactly("confirm");
    }

    @Test
    @DisplayName("结算单列表：PAID 终态无动作（打款不可逆，不再给任何按钮）")
    void 终态无动作() {
        when(settlementService.pageStatements(1, 20, null, null))
                .thenReturn(PageResult.of(List.of(statement(SettlementStatus.PAID.getCode(), 7L)), 1, 1, 20));

        AdminViews.SettlementVO vo = service.settlementPage(1, 20, null, null).getList().get(0);

        assertThat(vo.allowedActions()).isEmpty();
    }

    @Test
    @DisplayName("直营结算单（agentId=null）：代理名固定为直营，不去查代理表")
    void 直营单归属() {
        when(settlementService.pageStatements(1, 20, null, null))
                .thenReturn(PageResult.of(List.of(statement(SettlementStatus.CONFIRMED.getCode(), null)), 1, 1, 20));

        AdminViews.SettlementVO vo = service.settlementPage(1, 20, null, null).getList().get(0);

        assertThat(vo.agentName()).isEqualTo("直营");
        assertThat(vo.allowedActions()).containsExactly("paid");
    }

    @Test
    @DisplayName("结算单详情：单头 + 挂单流水（含负数冲正行，符号不丢）")
    void 详情聚合() {
        when(settlementService.statementById(5L)).thenReturn(statement(SettlementStatus.CONFIRMED.getCode(), 7L));
        OrderSettlementEntity order = new OrderSettlementEntity();
        order.setId(1L);
        order.setOrderNo("SWO-1");
        order.setEventType("ORDER");
        order.setBaseType("CASH");
        order.setBaseAmountFen(300);
        order.setAgentShareFen(90);
        order.setPlatformShareFen(210);
        order.setSubsidyFen(0);
        OrderSettlementEntity reversal = new OrderSettlementEntity();
        reversal.setId(2L);
        reversal.setOrderNo("SWO-1");
        reversal.setEventType("REFUND_REVERSAL");
        reversal.setBaseType("REVERSAL");
        reversal.setBaseAmountFen(-300);
        reversal.setAgentShareFen(-90);
        reversal.setPlatformShareFen(-210);
        when(orderSettlementDao.selectList(any())).thenReturn(List.of(order, reversal));

        AdminViews.SettlementDetailVO detail = service.settlementDetail(5L);

        assertThat(detail.statement().statementNo()).isEqualTo("ST1001");
        assertThat(detail.lines()).hasSize(2);
        assertThat(detail.lines().get(1).baseAmountFen()).isEqualTo(-300);
        assertThat(detail.lines().get(1).eventType()).isEqualTo("REFUND_REVERSAL");
    }

    @Test
    @DisplayName("结算单不存在：400 业务错（不静默返回空视图）")
    void 不存在() {
        when(settlementService.statementById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.settlementDetail(404L))
                .isInstanceOf(RRException.class).hasMessageContaining("结算单不存在");
    }
}
