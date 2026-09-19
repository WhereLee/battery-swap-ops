package com.swapops.server.settlement.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.swapops.server.common.RRException;
import com.swapops.server.common.id.SnowflakeIdGenerator;
import com.swapops.server.common.utils.PageParams;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.order.entity.RefundRecordEntity;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.settlement.dao.AgentDao;
import com.swapops.server.settlement.dao.OrderSettlementDao;
import com.swapops.server.settlement.dao.SettlementStatementDao;
import com.swapops.server.settlement.entity.AgentEntity;
import com.swapops.server.settlement.entity.OrderSettlementEntity;
import com.swapops.server.settlement.entity.SettlementStatementEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分润结算（S7 WP-B）：
 * <ul>
 *   <li><b>append-only 流水</b>：ORDER（完成单分账）/ REFUND_REVERSAL（退款冲正负行）/ ARREARS_SETTLE（欠费补缴补行），
 *       event_key 唯一=幂等闸（事件重放安全）；</li>
 *   <li><b>基数口径</b>：CASH=实收+券抵扣（券为平台补贴，代理不因用券少分）；次卡=单价折算 floor(price/times)；
 *       月卡=0（仅计次，协议化留后）；冲正=-退款额；补缴=+实缴额；</li>
 *   <li><b>分配</b>：long 运算 floor(base×share_bp/10000)，platform=base-agent（不丢分）；</li>
 *   <li><b>结算单</b>：顺序批（agent 未挂单流水），CAS 抢挂；PAID 不可变；迟到/冲正/补缴进下一期；直营不生成。</li>
 * </ul>
 */
@Slf4j
@Service
public class SettlementService {

    private final AgentDao agentDao;
    private final OrderSettlementDao orderSettlementDao;
    private final SettlementStatementDao statementDao;
    private final com.swapops.server.asset.dao.StationDao stationDao;
    private final com.swapops.server.user.dao.UserPlanDao userPlanDao;
    private final com.swapops.server.user.dao.PlanDao planDao;
    private final SnowflakeIdGenerator idGenerator;

    public SettlementService(AgentDao agentDao, OrderSettlementDao orderSettlementDao,
                             SettlementStatementDao statementDao,
                             com.swapops.server.asset.dao.StationDao stationDao,
                             com.swapops.server.user.dao.UserPlanDao userPlanDao,
                             com.swapops.server.user.dao.PlanDao planDao,
                             SnowflakeIdGenerator idGenerator) {
        this.agentDao = agentDao;
        this.orderSettlementDao = orderSettlementDao;
        this.statementDao = statementDao;
        this.stationDao = stationDao;
        this.userPlanDao = userPlanDao;
        this.planDao = planDao;
        this.idGenerator = idGenerator;
    }

    // ---------- 流水写入（业务事务内调用） ----------

    /** 订单完成分账（BillingService.charge 末调用；幂等：event_key 唯一） */
    public void settleOrder(SwapOrderEntity order) {
        if (order == null || order.getOrderNo() == null || order.getPayType() == null) {
            return; // RETURN 无计费不入分账
        }
        String baseType;
        int baseAmount;
        int subsidy = 0;
        if ("PLAN".equals(order.getPayType())) {
            var userPlan = order.getUserPlanId() == null ? null : userPlanDao.selectById(order.getUserPlanId());
            var plan = userPlan == null ? null : planDao.selectById(userPlan.getPlanId());
            if (plan != null && plan.getTotalTimes() != null && plan.getTotalTimes() > 0) {
                baseType = "PLAN_TIMES";
                baseAmount = (plan.getPriceFen() == null ? 0 : plan.getPriceFen()) / plan.getTotalTimes();
            } else {
                baseType = "PLAN_MONTHLY";
                baseAmount = 0;
            }
        } else {
            baseType = "CASH";
            int fee = order.getFeeFen() == null ? 0 : order.getFeeFen();
            subsidy = order.getDiscountFen() == null ? 0 : order.getDiscountFen();
            baseAmount = fee + subsidy;
        }
        writeLine(order.getOrderNo(), ":ORDER", order.getId(), order.getStationId(),
                "ORDER", baseType, baseAmount, subsidy);
    }

    /** 退款冲正（RefundService.apply 成功后调用；仅对已分账订单生效；按原单代理与<b>原单比例</b>冲正） */
    public void recordRefundReversal(SwapOrderEntity order, RefundRecordEntity refund) {
        if (order == null || refund == null || refund.getAmountFen() == null || refund.getAmountFen() <= 0) {
            return;
        }
        OrderSettlementEntity origin = originLine(order.getOrderNo());
        if (origin == null) {
            log.info("[分账] 退款冲正跳过（订单未分账） orderNo={} refundNo={}", order.getOrderNo(), refund.getRefundNo());
            return;
        }
        writeOriginProportionalLine(order.getOrderNo(), ":REV:" + refund.getRefundNo(), order.getId(),
                origin, "REFUND_REVERSAL", "REVERSAL", -refund.getAmountFen());
    }

    /** 欠费补缴补行（ArrearsService.pay 结清后调用；实缴部分确认收入，同样按原单比例） */
    public void recordArrearsSettlement(String orderNo, Long arrearsId, Long orderId, int paidFen) {
        if (orderNo == null || paidFen <= 0) {
            return;
        }
        OrderSettlementEntity origin = originLine(orderNo);
        if (origin == null) {
            log.info("[分账] 欠费补缴补行跳过（订单未分账） orderNo={} arrearsId={}", orderNo, arrearsId);
            return;
        }
        writeOriginProportionalLine(orderNo, ":ARR:" + arrearsId, orderId, origin,
                "ARREARS_SETTLE", "ARREARS", paidFen);
    }

    /** 原单分账行（{@code orderNo:ORDER}）——冲正/补缴的唯一参照系。 */
    private OrderSettlementEntity originLine(String orderNo) {
        return orderSettlementDao.selectOne(new LambdaQueryWrapper<OrderSettlementEntity>()
                .eq(OrderSettlementEntity::getEventKey, orderNo + ":ORDER"));
    }

    /**
     * 按<b>原单比例</b>写冲正/补缴行（批次35 修复 AUD-5）。
     *
     * <p>为什么不能按"代理当前 shareBp"重算：ORDER 行按订单完成时的比例分成，而 {@code AgentService.update}
     * 允许随时改比例。若冲正按现读比例算，比例一下调，代理被少扣（差额被代理白拿）；一上调则多扣。
     * 更糟的是对账发现不了——既有的"分账守恒"不变量只校验单行 {@code agent+platform=base}，
     * 两行各自守恒、比例却不同，跨行口径漂移是隐形的。
     *
     * <p>口径：{@code agentShare = amount × origin.agentShareFen ÷ origin.baseAmountFen}（同号取整，
     * 与 ORDER 行的 floor 口径一致），{@code platform = amount - agentShare}（不丢分）。
     * 月卡订单的原单基数为 0（PLAN_MONTHLY 计次不分账），此时无比例可言——回落为"全部平台"并告警，
     * 不静默按 0 分摊。
     */
    private void writeOriginProportionalLine(String orderNo, String keySuffix, Long orderId,
                                             OrderSettlementEntity origin, String eventType,
                                             String baseType, int amount) {
        Integer originBase = origin.getBaseAmountFen();
        Integer originAgentShare = origin.getAgentShareFen();
        int agentShare = 0;
        if (originBase == null || originBase == 0 || originAgentShare == null) {
            if (originAgentShare != null && originAgentShare != 0) {
                log.warn("[分账] 原单基数异常，冲正按全平台记 orderNo={} originBase={} originAgentShare={}",
                        orderNo, originBase, originAgentShare);
            }
        } else {
            agentShare = (int) ((long) amount * originAgentShare / originBase);
        }
        OrderSettlementEntity line = new OrderSettlementEntity();
        line.setEventKey(orderNo + keySuffix);
        line.setOrderId(orderId);
        line.setOrderNo(orderNo);
        line.setStationId(origin.getStationId());
        line.setAgentId(origin.getAgentId());
        line.setEventType(eventType);
        line.setBaseType(baseType);
        line.setBaseAmountFen(amount);
        line.setAgentShareFen(agentShare);
        line.setPlatformShareFen(amount - agentShare);
        line.setSubsidyFen(0);
        line.setCreateTime(System.currentTimeMillis());
        try {
            orderSettlementDao.insert(line);
            log.info("[分账] {} orderNo={} amount={} agent={} platform={}（按原单比例 {}‰ 口径）",
                    eventType, orderNo, amount, agentShare, amount - agentShare,
                    originBase == null || originBase == 0 ? "n/a"
                            : String.valueOf((int) ((long) originAgentShare * 1000 / originBase)));
        } catch (DuplicateKeyException e) {
            log.debug("[分账] 幂等命中（已入账） key={}", line.getEventKey());
        }
    }

    /**
     * 原单（ORDER）行写入：幂等（{@code event_key} 重复=已入账忽略）+ 按<b>站点当前归属</b>解析代理与比例。
     *
     * <p>只有"订单首次分账"走这里；冲正与补缴走 {@link #writeOriginProportionalLine}
     * （它们必须复用原单的比例，不能现读代理配置——见该方法的说明）。
     */
    private void writeLine(String orderNo, String keySuffix, Long orderId, Long stationId,
                           String eventType, String baseType, int baseAmount, int subsidy) {
        Long agentId = null;
        Integer shareBp = 0;
        if (stationId != null) {
            var station = stationDao.selectById(stationId);
            if (station != null && station.getAgentId() != null) {
                AgentEntity agent = agentDao.selectById(station.getAgentId());
                if (agent != null) {
                    agentId = agent.getId();
                    shareBp = agent.getShareBp() == null ? 0 : agent.getShareBp();
                }
            }
        }
        long agentShare = (long) baseAmount * shareBp / 10000L;
        OrderSettlementEntity line = new OrderSettlementEntity();
        line.setEventKey(orderNo + keySuffix);
        line.setOrderId(orderId);
        line.setOrderNo(orderNo);
        line.setStationId(stationId);
        line.setAgentId(agentId);
        line.setEventType(eventType);
        line.setBaseType(baseType);
        line.setBaseAmountFen(baseAmount);
        line.setAgentShareFen((int) agentShare);
        line.setPlatformShareFen(baseAmount - (int) agentShare);
        line.setSubsidyFen(subsidy);
        line.setCreateTime(System.currentTimeMillis());
        try {
            orderSettlementDao.insert(line);
            log.info("[分账] {} orderNo={} base={} agent={} platform={} agentId={}",
                    eventType, orderNo, baseAmount, agentShare, baseAmount - (int) agentShare, agentId);
        } catch (DuplicateKeyException e) {
            log.debug("[分账] 幂等命中（已入账） key={}", line.getEventKey());
        }
    }

    // ---------- 结算单 ----------

    /**
     * 生成结算单（顺序批：该代理全部未挂单流水；PAID 不可变，迟到流水进下一期）。
     * 抢挂用条件 UPDATE（statement_id IS NULL）防并发生成双领。
     */
    @Transactional
    public SettlementStatementEntity generate(Long agentId, long periodStart, long periodEnd, String operator) {
        AgentEntity agent = agentDao.selectById(agentId);
        if (agent == null) {
            throw new RRException("代理不存在: " + agentId);
        }
        if (periodStart > periodEnd) {
            throw new RRException("周期起止非法");
        }
        List<OrderSettlementEntity> candidates = orderSettlementDao.selectList(
                new LambdaQueryWrapper<OrderSettlementEntity>()
                        .eq(OrderSettlementEntity::getAgentId, agentId)
                        .isNull(OrderSettlementEntity::getStatementId)
                        .orderByAsc(OrderSettlementEntity::getId)
                        .last("LIMIT 5000"));
        if (candidates.isEmpty()) {
            throw new RRException("无可结算流水（该代理已结清）: agentId=" + agentId);
        }
        long now = System.currentTimeMillis();
        SettlementStatementEntity statement = new SettlementStatementEntity();
        statement.setStatementNo("ST" + idGenerator.nextIdString());
        statement.setAgentId(agentId);
        statement.setPeriodStart(periodStart);
        statement.setPeriodEnd(periodEnd);
        statement.setOrderCount(0);
        statement.setBaseAmountFen(0);
        statement.setAgentAmountFen(0);
        statement.setPlatformAmountFen(0);
        statement.setSubsidyFen(0);
        statement.setStatus(1);
        statement.setGeneratedBy(operator);
        statement.setGeneratedTime(now);
        statement.setCreateTime(now);
        statement.setUpdateTime(now);
        try {
            statementDao.insert(statement);
        } catch (DuplicateKeyException e) {
            throw new RRException("该周期结算单已存在（同代理同周期仅一张）");
        }
        int claimed = orderSettlementDao.update(null, new LambdaUpdateWrapper<OrderSettlementEntity>()
                .eq(OrderSettlementEntity::getAgentId, agentId)
                .isNull(OrderSettlementEntity::getStatementId)
                .in(OrderSettlementEntity::getId, candidates.stream().map(OrderSettlementEntity::getId).toList())
                .set(OrderSettlementEntity::getStatementId, statement.getId()));
        if (claimed == 0) {
            statementDao.deleteById(statement.getId());
            throw new RRException("流水被并发生成领取，请重试");
        }
        // 以实际挂单流水聚合（权威口径）
        List<OrderSettlementEntity> lines = orderSettlementDao.selectList(
                new LambdaQueryWrapper<OrderSettlementEntity>()
                        .eq(OrderSettlementEntity::getStatementId, statement.getId()));
        int base = 0;
        int agentAmount = 0;
        int platform = 0;
        int subsidy = 0;
        int orderCount = 0;
        for (OrderSettlementEntity line : lines) {
            base += line.getBaseAmountFen() == null ? 0 : line.getBaseAmountFen();
            agentAmount += line.getAgentShareFen() == null ? 0 : line.getAgentShareFen();
            platform += line.getPlatformShareFen() == null ? 0 : line.getPlatformShareFen();
            subsidy += line.getSubsidyFen() == null ? 0 : line.getSubsidyFen();
            if ("ORDER".equals(line.getEventType())) {
                orderCount++;
            }
        }
        statementDao.update(null, new LambdaUpdateWrapper<SettlementStatementEntity>()
                .eq(SettlementStatementEntity::getId, statement.getId())
                .set(SettlementStatementEntity::getBaseAmountFen, base)
                .set(SettlementStatementEntity::getAgentAmountFen, agentAmount)
                .set(SettlementStatementEntity::getPlatformAmountFen, platform)
                .set(SettlementStatementEntity::getSubsidyFen, subsidy)
                .set(SettlementStatementEntity::getOrderCount, orderCount)
                .set(SettlementStatementEntity::getUpdateTime, System.currentTimeMillis()));
        log.info("[结算] 生成 {} agentId={} lines={} base={} agent={} platform={}",
                statement.getStatementNo(), agentId, lines.size(), base, agentAmount, platform);
        return statementDao.selectById(statement.getId());
    }

    public SettlementStatementEntity confirm(Long id, String operator) {
        return transit(id, 1, 2, operator, "confirmed");
    }

    public SettlementStatementEntity pay(Long id, String operator) {
        return transit(id, 2, 3, operator, "paid");
    }

    public SettlementStatementEntity transit(Long id, int from, int to, String operator, String phase) {
        SettlementStatementEntity statement = requireStatement(id);
        if (statement.getStatus() == null || statement.getStatus() != from) {
            throw new RRException("结算单状态不允许该操作（当前 " + statement.getStatus() + "，期望 " + from + "）");
        }
        long now = System.currentTimeMillis();
        LambdaUpdateWrapper<SettlementStatementEntity> wrapper = new LambdaUpdateWrapper<SettlementStatementEntity>()
                .eq(SettlementStatementEntity::getId, id)
                .eq(SettlementStatementEntity::getStatus, from)
                .set(SettlementStatementEntity::getStatus, to)
                .set(SettlementStatementEntity::getUpdateTime, now);
        if ("confirmed".equals(phase)) {
            wrapper.set(SettlementStatementEntity::getConfirmedBy, operator)
                    .set(SettlementStatementEntity::getConfirmedTime, now);
        } else {
            wrapper.set(SettlementStatementEntity::getPaidBy, operator)
                    .set(SettlementStatementEntity::getPaidTime, now);
        }
        int rows = statementDao.update(null, wrapper);
        if (rows == 0) {
            throw new RRException("结算单状态已变更，请刷新重试: " + id);
        }
        log.info("[结算] 状态推进 id={} {} -> {} by={}", id, from, to, operator);
        return statementDao.selectById(id);
    }

    public List<SettlementStatementEntity> listStatements(Long agentId, Integer status) {
        return statementDao.selectList(new LambdaQueryWrapper<SettlementStatementEntity>()
                .eq(agentId != null, SettlementStatementEntity::getAgentId, agentId)
                .eq(status != null, SettlementStatementEntity::getStatus, status)
                .orderByDesc(SettlementStatementEntity::getId)
                .last("LIMIT 200"));
    }

    /**
     * 结算单分页（S8 批次31 管理台列表）：与 {@link #listStatements} 同序（id 倒序），
     * 但走真实分页（COUNT + LIMIT 由 PaginationInnerInterceptor 注入）——
     * 列表页不能再用"拉 200 条截断"的口径冒充分页（批次30 的假分页缺陷教训）。
     */
    public com.swapops.server.common.utils.PageResult<SettlementStatementEntity> pageStatements(
            Integer page, Integer limit, Long agentId, Integer status) {
        IPage<SettlementStatementEntity> result = statementDao.selectPage(
                new Page<>(PageParams.page(page), PageParams.limit(limit)),
                new LambdaQueryWrapper<SettlementStatementEntity>()
                        .eq(agentId != null, SettlementStatementEntity::getAgentId, agentId)
                        .eq(status != null, SettlementStatementEntity::getStatus, status)
                        .orderByDesc(SettlementStatementEntity::getId));
        return PageResult.of(result);
    }

    public Map<String, Object> statementDetail(Long id) {
        SettlementStatementEntity statement = requireStatement(id);
        List<OrderSettlementEntity> lines = orderSettlementDao.selectList(
                new LambdaQueryWrapper<OrderSettlementEntity>()
                        .eq(OrderSettlementEntity::getStatementId, id)
                        .orderByAsc(OrderSettlementEntity::getId)
                        .last("LIMIT 500"));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("statement", statement);
        view.put("lines", lines);
        return view;
    }

    /** 某订单的分账流水（对账/排障/剧本断言用） */
    public List<OrderSettlementEntity> ledgerByOrder(String orderNo) {
        return orderSettlementDao.selectList(new LambdaQueryWrapper<OrderSettlementEntity>()
                .eq(OrderSettlementEntity::getOrderNo, orderNo)
                .orderByAsc(OrderSettlementEntity::getId));
    }

    /** 结算报表（按代理聚合 + 直营汇总；时间段按流水 create_time） */
    public Map<String, Object> report(long from, long to) {
        List<OrderSettlementEntity> lines = orderSettlementDao.selectList(
                new LambdaQueryWrapper<OrderSettlementEntity>()
                        .ge(OrderSettlementEntity::getCreateTime, from)
                        .lt(OrderSettlementEntity::getCreateTime, to)
                        .orderByAsc(OrderSettlementEntity::getId)
                        .last("LIMIT 20000"));
        Map<Long, Map<String, Object>> byAgent = new LinkedHashMap<>();
        Map<String, Object> direct = directBucket();
        for (OrderSettlementEntity line : lines) {
            Map<String, Object> bucket;
            if (line.getAgentId() == null) {
                bucket = direct;
            } else {
                bucket = byAgent.computeIfAbsent(line.getAgentId(), id -> agentBucket(id));
            }
            accumulate(bucket, line);
        }
        List<Map<String, Object>> agents = new ArrayList<>(byAgent.values());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", to);
        result.put("agents", agents);
        result.put("direct", direct);
        return result;
    }

    private Map<String, Object> agentBucket(Long agentId) {
        Map<String, Object> bucket = new LinkedHashMap<>();
        AgentEntity agent = agentDao.selectById(agentId);
        bucket.put("agentId", agentId);
        bucket.put("agentNo", agent == null ? null : agent.getAgentNo());
        bucket.put("agentName", agent == null ? null : agent.getName());
        return bucket;
    }

    private Map<String, Object> directBucket() {
        Map<String, Object> bucket = new LinkedHashMap<>();
        bucket.put("agentId", null);
        bucket.put("agentNo", "DIRECT");
        bucket.put("agentName", "直营");
        return bucket;
    }

    private void accumulate(Map<String, Object> bucket, OrderSettlementEntity line) {
        add(bucket, "lines", 1);
        add(bucket, "baseAmountFen", line.getBaseAmountFen() == null ? 0 : line.getBaseAmountFen());
        add(bucket, "agentAmountFen", line.getAgentShareFen() == null ? 0 : line.getAgentShareFen());
        add(bucket, "platformAmountFen",
                line.getPlatformShareFen() == null ? 0 : line.getPlatformShareFen());
        add(bucket, "subsidyFen", line.getSubsidyFen() == null ? 0 : line.getSubsidyFen());
    }

    private void add(Map<String, Object> bucket, String key, int value) {
        bucket.merge(key, value, (a, b) -> (Integer) a + (Integer) b);
    }

    /** 结算单按 id 读取（S8 批次31 视图层用）：不存在返回 null，由调用方决定是 400 还是空视图。 */
    public SettlementStatementEntity statementById(Long id) {
        return id == null ? null : statementDao.selectById(id);
    }

    private SettlementStatementEntity requireStatement(Long id) {
        SettlementStatementEntity statement = statementById(id);
        if (statement == null) {
            throw new RRException("结算单不存在: " + id);
        }
        return statement;
    }
}
