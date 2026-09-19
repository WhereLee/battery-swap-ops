package com.swapops.server.web.view.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.server.common.RRException;
import com.swapops.server.common.action.ActionsSupport;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.settlement.dao.AgentDao;
import com.swapops.server.settlement.dao.OrderSettlementDao;
import com.swapops.server.settlement.entity.AgentEntity;
import com.swapops.server.settlement.entity.OrderSettlementEntity;
import com.swapops.server.settlement.entity.SettlementStatementEntity;
import com.swapops.server.settlement.service.SettlementService;
import com.swapops.server.web.view.AdminViews;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金口视图（S8 批次31）：结算单列表 / 结算单详情（单头 + 分账流水）。
 *
 * <p>三条纪律与流程口视图一致：
 * <ol>
 *   <li><b>只读</b>：本层不生成、不确认、不打款——状态推进仍走
 *       {@code /admin/settlement/{id}/confirm|paid}（各自鉴权 + 各自审计）；</li>
 *   <li><b>能力位由服务端给</b>：{@code allowedActions} 来自 {@link ActionsSupport#settlement}，
 *       前端不复制"1 能确认、2 能打款"的规则；</li>
 *   <li><b>不打 @DataFilter</b>：结算维度是代理商而非站点，属全局口径资源（与 alarm/agent_action 同处理，
 *       见 S8 方案 §4.8）——受限身份看结算列表不等于越权，真正的站点隔离在工单/柜/订单三域。</li>
 * </ol>
 *
 * <p>金额一律整数分、原样透出（不在视图层做任何加减）：结算单的聚合口径是
 * {@code SettlementService.generate} 挂单后的权威结果，视图层重算一遍就是第二个口径。
 */
@Service
public class AdminFinanceViewService {

    /** 详情内嵌流水上限：单据正常在数百行内；超出说明该期异常，需走报表/对账而不是翻页看流水。 */
    private static final int LINE_LIMIT = 500;

    private final SettlementService settlementService;
    private final OrderSettlementDao orderSettlementDao;
    private final AgentDao agentDao;

    public AdminFinanceViewService(SettlementService settlementService, OrderSettlementDao orderSettlementDao,
                                   AgentDao agentDao) {
        this.settlementService = settlementService;
        this.orderSettlementDao = orderSettlementDao;
        this.agentDao = agentDao;
    }

    /** 结算单分页视图：代理名一次批量解析（列表不 N+1）。 */
    public PageResult<AdminViews.SettlementVO> settlementPage(Integer page, Integer limit, Long agentId,
                                                              Integer status) {
        PageResult<SettlementStatementEntity> raw = settlementService.pageStatements(page, limit, agentId, status);
        List<SettlementStatementEntity> statements = raw.getList();
        Map<Long, String> agentNames = agentNamesOf(statements);

        List<AdminViews.SettlementVO> views = new ArrayList<>(statements.size());
        for (SettlementStatementEntity statement : statements) {
            views.add(toVO(statement, agentNames));
        }
        return PageResult.of(views, raw.getTotal(), raw.getPage(), raw.getLimit());
    }

    /** 结算单详情：单头（含能力位）+ 挂在该单下的分账流水。 */
    public AdminViews.SettlementDetailVO settlementDetail(Long id) {
        SettlementStatementEntity statement = settlementService.statementById(id);
        if (statement == null) {
            throw new RRException("结算单不存在: " + id);
        }
        List<OrderSettlementEntity> lines = orderSettlementDao.selectList(
                new LambdaQueryWrapper<OrderSettlementEntity>()
                        .eq(OrderSettlementEntity::getStatementId, id)
                        .orderByAsc(OrderSettlementEntity::getId)
                        .last("LIMIT " + LINE_LIMIT));
        List<AdminViews.SettlementLineVO> lineViews = new ArrayList<>(lines.size());
        for (OrderSettlementEntity line : lines) {
            lineViews.add(new AdminViews.SettlementLineVO(line.getId(), line.getOrderNo(), line.getStationId(),
                    line.getEventType(), line.getBaseType(), line.getBaseAmountFen(), line.getAgentShareFen(),
                    line.getPlatformShareFen(), line.getSubsidyFen(), line.getCreateTime()));
        }
        return new AdminViews.SettlementDetailVO(toVO(statement, agentNamesOf(List.of(statement))),
                List.copyOf(lineViews));
    }

    private AdminViews.SettlementVO toVO(SettlementStatementEntity statement, Map<Long, String> agentNames) {
        Long agentId = statement.getAgentId();
        return new AdminViews.SettlementVO(statement.getId(), statement.getStatementNo(), agentId,
                agentId == null ? "直营" : agentNames.getOrDefault(agentId, null),
                statement.getPeriodStart(), statement.getPeriodEnd(), statement.getOrderCount(),
                statement.getBaseAmountFen(), statement.getAgentAmountFen(), statement.getPlatformAmountFen(),
                statement.getSubsidyFen(), statement.getStatus(), statement.getGeneratedBy(),
                statement.getConfirmedBy(), statement.getPaidBy(), statement.getGeneratedTime(),
                statement.getConfirmedTime(), statement.getPaidTime(), statement.getRemark(),
                statement.getCreateTime(), statement.getUpdateTime(),
                ActionsSupport.settlement(statement.getStatus()));
    }

    /** 一页结算单的代理名映射（一次 in 查询；代理被删则名称为 null，不抛）。 */
    private Map<Long, String> agentNamesOf(List<SettlementStatementEntity> statements) {
        List<Long> agentIds = statements.stream().map(SettlementStatementEntity::getAgentId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> names = new LinkedHashMap<>();
        if (!agentIds.isEmpty()) {
            for (AgentEntity agent : agentDao.selectBatchIds(agentIds)) {
                names.put(agent.getId(), agent.getName());
            }
        }
        return names;
    }
}
