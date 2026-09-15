package com.swapops.server.settlement.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.common.RRException;
import com.swapops.server.common.id.SnowflakeIdGenerator;
import com.swapops.server.order.entity.RefundRecordEntity;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.settlement.dao.AgentDao;
import com.swapops.server.settlement.dao.OrderSettlementDao;
import com.swapops.server.settlement.dao.SettlementStatementDao;
import com.swapops.server.settlement.entity.AgentEntity;
import com.swapops.server.settlement.entity.OrderSettlementEntity;
import com.swapops.server.settlement.entity.SettlementStatementEntity;
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
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分润结算单测（S7 WP-B）：基数口径（CASH/次卡/月卡）/分成不丢分/幂等/冲正负行/补缴补行/
 * 结算单顺序批与 CAS/PAID 不可变。
 */
@DisplayName("分润结算")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementServiceTest {

    @Mock
    private AgentDao agentDao;
    @Mock
    private OrderSettlementDao orderSettlementDao;
    @Mock
    private SettlementStatementDao statementDao;
    @Mock
    private com.swapops.server.asset.dao.StationDao stationDao;
    @Mock
    private com.swapops.server.user.dao.UserPlanDao userPlanDao;
    @Mock
    private com.swapops.server.user.dao.PlanDao planDao;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    private SettlementService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentEntity.class);
        TableInfoHelper.initTableInfo(assistant, OrderSettlementEntity.class);
        TableInfoHelper.initTableInfo(assistant, SettlementStatementEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new SettlementService(agentDao, orderSettlementDao, statementDao, stationDao,
                userPlanDao, planDao, idGenerator);
    }

    private AgentEntity agent(long id, int bp) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setAgentNo("AG00" + id);
        agent.setShareBp(bp);
        agent.setStatus(1);
        return agent;
    }

    private SwapOrderEntity order(String type, String payType, int fee, int discount, Long stationId) {
        SwapOrderEntity order = new SwapOrderEntity();
        order.setId(99L);
        order.setOrderNo("SWO-1");
        order.setOrderType(type);
        order.setPayType(payType);
        order.setFeeFen(fee);
        order.setDiscountFen(discount);
        order.setStationId(stationId);
        return order;
    }

    private void stationBelongsTo(long stationId, long agentId) {
        com.swapops.server.asset.entity.StationEntity station =
                new com.swapops.server.asset.entity.StationEntity();
        station.setId(stationId);
        station.setAgentId(agentId);
        when(stationDao.selectById(stationId)).thenReturn(station);
        when(agentDao.selectById(agentId)).thenReturn(agent(agentId, agentId == 1L ? 6000 : 8000));
    }

    @Test
    @DisplayName("CASH 基数=实收+券抵扣；分成不丢分（6000bp）")
    void cash基数与分成() {
        stationBelongsTo(7L, 1L);
        when(orderSettlementDao.insert(any(OrderSettlementEntity.class))).thenReturn(1);

        service.settleOrder(order("SWAP", "BALANCE", 200, 100, 7L));

        ArgumentCaptor<OrderSettlementEntity> captor = ArgumentCaptor.forClass(OrderSettlementEntity.class);
        verify(orderSettlementDao).insert(captor.capture());
        OrderSettlementEntity line = captor.getValue();
        assertThat(line.getEventKey()).isEqualTo("SWO-1:ORDER");
        assertThat(line.getBaseType()).isEqualTo("CASH");
        assertThat(line.getBaseAmountFen()).isEqualTo(300);
        assertThat(line.getAgentShareFen()).isEqualTo(180);
        assertThat(line.getPlatformShareFen()).isEqualTo(120);
        assertThat(line.getSubsidyFen()).isEqualTo(100);
        assertThat(line.getAgentId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("次卡折算 floor(price/times)（整除余数归平台）；月卡基数 0；RETURN 不分账")
    void 套餐口径() {
        stationBelongsTo(7L, 1L);
        when(orderSettlementDao.insert(any(OrderSettlementEntity.class))).thenReturn(1);
        com.swapops.server.user.entity.UserPlanEntity up = new com.swapops.server.user.entity.UserPlanEntity();
        up.setId(5L);
        up.setPlanId(3L);
        when(userPlanDao.selectById(5L)).thenReturn(up);
        com.swapops.server.user.entity.PlanEntity plan = new com.swapops.server.user.entity.PlanEntity();
        plan.setPriceFen(3000);
        plan.setTotalTimes(10);
        when(planDao.selectById(3L)).thenReturn(plan);

        SwapOrderEntity timesOrder = order("TAKE", "PLAN", 0, 0, 7L);
        timesOrder.setUserPlanId(5L);
        service.settleOrder(timesOrder);

        ArgumentCaptor<OrderSettlementEntity> captor = ArgumentCaptor.forClass(OrderSettlementEntity.class);
        verify(orderSettlementDao).insert(captor.capture());
        OrderSettlementEntity line = captor.getValue();
        assertThat(line.getBaseType()).isEqualTo("PLAN_TIMES");
        assertThat(line.getBaseAmountFen()).isEqualTo(300);
        assertThat(line.getAgentShareFen()).isEqualTo(180);

        plan.setTotalTimes(null); // 月卡
        service.settleOrder(timesOrder);
        ArgumentCaptor<OrderSettlementEntity> captor2 = ArgumentCaptor.forClass(OrderSettlementEntity.class);
        verify(orderSettlementDao, org.mockito.Mockito.times(2)).insert(captor2.capture());
        OrderSettlementEntity monthly = captor2.getAllValues().get(1);
        assertThat(monthly.getBaseType()).isEqualTo("PLAN_MONTHLY");
        assertThat(monthly.getBaseAmountFen()).isZero();

        service.settleOrder(order("RETURN", null, 0, 0, 7L));
        verify(orderSettlementDao, org.mockito.Mockito.times(2)).insert(any(OrderSettlementEntity.class));
    }

    @Test
    @DisplayName("冲正：按原单代理写负向行；未分账订单跳过")
    void 冲正负行() {
        OrderSettlementEntity origin = new OrderSettlementEntity();
        origin.setEventKey("SWO-1:ORDER");
        origin.setStationId(7L);
        origin.setAgentId(1L);
        when(orderSettlementDao.selectOne(any())).thenReturn(origin);
        when(agentDao.selectById(1L)).thenReturn(agent(1L, 6000));
        when(orderSettlementDao.insert(any(OrderSettlementEntity.class))).thenReturn(1);
        RefundRecordEntity refund = new RefundRecordEntity();
        refund.setRefundNo("RF1");
        refund.setAmountFen(100);

        service.recordRefundReversal(order("SWAP", "BALANCE", 200, 0, 7L), refund);

        ArgumentCaptor<OrderSettlementEntity> captor = ArgumentCaptor.forClass(OrderSettlementEntity.class);
        verify(orderSettlementDao).insert(captor.capture());
        OrderSettlementEntity line = captor.getValue();
        assertThat(line.getEventType()).isEqualTo("REFUND_REVERSAL");
        assertThat(line.getEventKey()).isEqualTo("SWO-1:REV:RF1");
        assertThat(line.getBaseAmountFen()).isEqualTo(-100);
        assertThat(line.getAgentShareFen()).isEqualTo(-60);
        assertThat(line.getPlatformShareFen()).isEqualTo(-40);

        when(orderSettlementDao.selectOne(any())).thenReturn(null);
        service.recordRefundReversal(order("SWAP", "BALANCE", 200, 0, 7L), refund);
        verify(orderSettlementDao, org.mockito.Mockito.times(1)).insert(any(OrderSettlementEntity.class));
    }

    @Test
    @DisplayName("补缴补行：正行按原单代理；未分账跳过；金额<=0 跳过")
    void 补缴补行() {
        OrderSettlementEntity origin = new OrderSettlementEntity();
        origin.setStationId(7L);
        origin.setAgentId(1L);
        when(orderSettlementDao.selectOne(any())).thenReturn(origin);
        when(agentDao.selectById(1L)).thenReturn(agent(1L, 6000));
        when(orderSettlementDao.insert(any(OrderSettlementEntity.class))).thenReturn(1);

        service.recordArrearsSettlement("SWO-1", 11L, 99L, 100);

        ArgumentCaptor<OrderSettlementEntity> captor = ArgumentCaptor.forClass(OrderSettlementEntity.class);
        verify(orderSettlementDao).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("ARREARS_SETTLE");
        assertThat(captor.getValue().getEventKey()).isEqualTo("SWO-1:ARR:11");
        assertThat(captor.getValue().getBaseAmountFen()).isEqualTo(100);

        service.recordArrearsSettlement("SWO-1", 11L, 99L, 0);
        verify(orderSettlementDao, org.mockito.Mockito.times(1)).insert(any(OrderSettlementEntity.class));
    }

    @Test
    @DisplayName("幂等：event_key 撞唯一键忽略（事件重放安全）")
    void 幂等() {
        stationBelongsTo(7L, 1L);
        when(orderSettlementDao.insert(any(OrderSettlementEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_settle_event"));
        service.settleOrder(order("SWAP", "BALANCE", 300, 0, 7L));
        // 不抛出即幂等吸收
    }

    @Test
    @DisplayName("结算单：无可结拒绝；同周期重复拒绝；顺序批统计与 CAS 抢挂")
    void 结算单生成() {
        when(agentDao.selectById(1L)).thenReturn(agent(1L, 6000));
        when(orderSettlementDao.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.generate(1L, 0L, 1L, "admin"))
                .isInstanceOf(RRException.class).hasMessageContaining("无可结算流水");

        OrderSettlementEntity a = new OrderSettlementEntity();
        a.setId(1L);
        OrderSettlementEntity b = new OrderSettlementEntity();
        b.setId(2L);
        when(orderSettlementDao.selectList(any())).thenReturn(List.of(a, b), List.of(), List.of());
        when(idGenerator.nextIdString()).thenReturn("123");
        when(statementDao.insert(any(SettlementStatementEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, SettlementStatementEntity.class).setId(50L);
            return 1;
        });
        when(orderSettlementDao.update(isNull(), any())).thenReturn(2);
        // 聚合读取（按 statement_id）
        OrderSettlementEntity line = new OrderSettlementEntity();
        line.setEventType("ORDER");
        line.setBaseAmountFen(300);
        line.setAgentShareFen(180);
        line.setPlatformShareFen(120);
        line.setSubsidyFen(0);
        when(orderSettlementDao.selectList(any())).thenReturn(List.of(a, b), List.of(), List.of(line));
        when(statementDao.selectById(50L)).thenReturn(statement(50L, 1));

        SettlementStatementEntity statement = service.generate(1L, 100L, 200L, "admin");

        assertThat(statement.getStatus()).isEqualTo(1);
        verify(statementDao, org.mockito.Mockito.atLeast(1)).update(isNull(), any());

        // 同周期重复：唯一键冲突 → 业务异常
        when(statementDao.insert(any(SettlementStatementEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_stmt_agent_period"));
        assertThatThrownBy(() -> service.generate(1L, 100L, 200L, "admin"))
                .isInstanceOf(RRException.class).hasMessageContaining("已存在");
    }

    private SettlementStatementEntity statement(long id, int status) {
        SettlementStatementEntity statement = new SettlementStatementEntity();
        statement.setId(id);
        statement.setStatementNo("ST1");
        statement.setStatus(status);
        return statement;
    }

    @Test
    @DisplayName("状态推进：GENERATED→CONFIRMED→PAID（PAID 后不可再推进）")
    void 状态推进() {
        when(statementDao.selectById(50L)).thenReturn(statement(50L, 1), statement(50L, 2), statement(50L, 3));
        when(statementDao.update(isNull(), any())).thenReturn(1);
        service.confirm(50L, "fin1");

        when(statementDao.selectById(50L)).thenReturn(statement(50L, 2), statement(50L, 3));
        service.pay(50L, "fin1");

        when(statementDao.selectById(50L)).thenReturn(statement(50L, 3));
        assertThatThrownBy(() -> service.pay(50L, "fin1"))
                .isInstanceOf(RRException.class).hasMessageContaining("不允许");
    }

    @Test
    @DisplayName("报表：按代理聚合 + 直营汇总")
    void 报表() {
        OrderSettlementEntity agentLine = new OrderSettlementEntity();
        agentLine.setAgentId(1L);
        agentLine.setBaseAmountFen(300);
        agentLine.setAgentShareFen(180);
        agentLine.setPlatformShareFen(120);
        agentLine.setSubsidyFen(100);
        OrderSettlementEntity directLine = new OrderSettlementEntity();
        directLine.setAgentId(null);
        directLine.setBaseAmountFen(500);
        directLine.setAgentShareFen(0);
        directLine.setPlatformShareFen(500);
        directLine.setSubsidyFen(0);
        when(orderSettlementDao.selectList(any())).thenReturn(List.of(agentLine, directLine));
        when(agentDao.selectById(1L)).thenReturn(agent(1L, 6000));

        var report = service.report(0L, Long.MAX_VALUE);

        @SuppressWarnings("unchecked")
        var agents = (List<java.util.Map<String, Object>>) report.get("agents");
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).get("agentAmountFen")).isEqualTo(180);
        @SuppressWarnings("unchecked")
        var direct = (java.util.Map<String, Object>) report.get("direct");
        assertThat(direct.get("platformAmountFen")).isEqualTo(500);
    }

    @Test
    @DisplayName("share_bp 边界：0=全平台 / 10000=全代理")
    void 分成边界() {
        com.swapops.server.asset.entity.StationEntity station =
                new com.swapops.server.asset.entity.StationEntity();
        station.setId(7L);
        station.setAgentId(1L);
        when(stationDao.selectById(7L)).thenReturn(station);
        when(orderSettlementDao.insert(any(OrderSettlementEntity.class))).thenReturn(1);

        when(agentDao.selectById(1L)).thenReturn(agent(1L, 0));
        service.settleOrder(order("SWAP", "BALANCE", 300, 0, 7L));
        ArgumentCaptor<OrderSettlementEntity> captor = ArgumentCaptor.forClass(OrderSettlementEntity.class);
        verify(orderSettlementDao).insert(captor.capture());
        assertThat(captor.getValue().getAgentShareFen()).isZero();
        assertThat(captor.getValue().getPlatformShareFen()).isEqualTo(300);

        when(agentDao.selectById(1L)).thenReturn(agent(1L, 10000));
        service.settleOrder(order("SWAP", "BALANCE", 300, 0, 7L));
        ArgumentCaptor<OrderSettlementEntity> captor2 = ArgumentCaptor.forClass(OrderSettlementEntity.class);
        verify(orderSettlementDao, org.mockito.Mockito.times(2)).insert(captor2.capture());
        assertThat(captor2.getAllValues().get(1).getAgentShareFen()).isEqualTo(300);
        assertThat(captor2.getAllValues().get(1).getPlatformShareFen()).isZero();
    }
}
