package com.swapops.server.web.view.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.contract.OrderStatus;
import com.swapops.server.admin.data.DataScopeSupport;
import com.swapops.server.alarm.dao.AlarmDao;
import com.swapops.server.alarm.entity.AlarmEntity;
import com.swapops.server.asset.service.AssetAdminService;
import com.swapops.server.common.RRException;
import com.swapops.server.common.action.ActionsSupport;
import com.swapops.server.common.utils.PageResult;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.dao.CommandLogDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.device.entity.CommandLogEntity;
import com.swapops.server.order.dao.PaymentRecordDao;
import com.swapops.server.order.dao.RefundRecordDao;
import com.swapops.server.order.dao.SwapOrderDao;
import com.swapops.server.order.entity.PaymentRecordEntity;
import com.swapops.server.order.entity.RefundRecordEntity;
import com.swapops.server.order.entity.SwapOrderEntity;
import com.swapops.server.order.service.SwapOrderService;
import com.swapops.server.order.service.pay.RefundService;
import com.swapops.server.web.view.AdminViews;
import com.swapops.server.workorder.entity.WorkOrderEntity;
import com.swapops.server.workorder.entity.WorkOrderLogEntity;
import com.swapops.server.workorder.service.WorkOrderService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程口视图（S8）：工单列表/详情、柜详情、订单详情。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>越权在取数处即拦</b>——工单走 {@code WorkOrderService.require}（内含 {@code requireStationAccess}），
 *       柜与订单在本层显式校验；聚合接口不得成为数据权限旁路。</li>
 *   <li><b>只读</b>：本层不写库、不改状态，动作仍走各域既有端点（前端按 {@code allowedActions} 调）。</li>
 *   <li><b>可退金额不由前端推算</b>：{@code refundableFen} 取 {@code RefundService} 的资金口径
 *       （押金二次退款事故的根因就是"可退额由流水反推"）。</li>
 * </ol>
 */
@Service
public class AdminWorkflowViewService {

    /** 柜详情内嵌列表的条数上限：页面只需最近若干条，避免一次拉全量历史。 */
    private static final int NESTED_LIMIT = 20;

    /** 订单"进行中"口径：非终态（终态 = COMPLETED/CANCELLED/TIMEOUT_CLOSED/EXCEPTION）。 */
    private static final List<Integer> ACTIVE_ORDER_STATUS = List.of(
            OrderStatus.PENDING_OPEN.getCode(), OrderStatus.OPENED.getCode(),
            OrderStatus.TAKEN.getCode(), OrderStatus.OVERDUE.getCode());

    private final WorkOrderService workOrderService;
    private final AlarmDao alarmDao;
    private final CabinetDao cabinetDao;
    private final CellDao cellDao;
    private final BatteryDao batteryDao;
    private final CommandLogDao commandLogDao;
    private final SwapOrderDao swapOrderDao;
    private final PaymentRecordDao paymentRecordDao;
    private final RefundRecordDao refundRecordDao;
    private final RefundService refundService;
    private final SwapOrderService swapOrderService;
    private final AssetAdminService assetAdminService;

    public AdminWorkflowViewService(WorkOrderService workOrderService, AlarmDao alarmDao, CabinetDao cabinetDao,
                                    CellDao cellDao, BatteryDao batteryDao, CommandLogDao commandLogDao,
                                    SwapOrderDao swapOrderDao, PaymentRecordDao paymentRecordDao,
                                    RefundRecordDao refundRecordDao, RefundService refundService,
                                    SwapOrderService swapOrderService, AssetAdminService assetAdminService) {
        this.workOrderService = workOrderService;
        this.alarmDao = alarmDao;
        this.cabinetDao = cabinetDao;
        this.cellDao = cellDao;
        this.batteryDao = batteryDao;
        this.commandLogDao = commandLogDao;
        this.swapOrderDao = swapOrderDao;
        this.paymentRecordDao = paymentRecordDao;
        this.refundRecordDao = refundRecordDao;
        this.refundService = refundService;
        this.swapOrderService = swapOrderService;
        this.assetAdminService = assetAdminService;
    }

    // ---------- 工单 ----------

    public PageResult<AdminViews.WorkOrderVO> workOrderPage(Integer page, Integer limit, Integer status) {
        PageResult<WorkOrderEntity> raw = workOrderService.page(page, limit, status); // 已含站点范围过滤
        List<AdminViews.WorkOrderVO> views = new ArrayList<>(raw.getList().size());
        for (WorkOrderEntity order : raw.getList()) {
            views.add(toWorkOrderVO(order));
        }
        return PageResult.of(views, raw.getTotal(), raw.getPage(), raw.getLimit());
    }

    public AdminViews.WorkOrderDetailVO workOrderDetail(Long id) {
        WorkOrderEntity order = workOrderService.require(id); // 越域 403 在 require 内
        List<AdminViews.WorkOrderLogVO> logs = new ArrayList<>();
        for (WorkOrderLogEntity log : workOrderService.logs(order.getWoNo())) {
            logs.add(new AdminViews.WorkOrderLogVO(log.getAction(), log.getFromStatus(), log.getToStatus(),
                    log.getOperator(), log.getRemark(), log.getCreateTime()));
        }
        AlarmEntity alarm = order.getAlarmId() == null ? null : alarmDao.selectById(order.getAlarmId());
        return new AdminViews.WorkOrderDetailVO(toWorkOrderVO(order), List.copyOf(logs), toAlarmBrief(alarm));
    }

    private AdminViews.WorkOrderVO toWorkOrderVO(WorkOrderEntity order) {
        return new AdminViews.WorkOrderVO(order.getId(), order.getWoNo(), order.getAlarmId(), order.getSource(),
                order.getReporterUserId(), order.getDescription(), order.getDeviceType(), order.getDeviceNo(),
                order.getStationId(), order.getTitle(), order.getSeverity(), order.getStatus(),
                order.getHandlerId(), order.getSlaDeadline(), order.getSlaBreached(), order.getVerifyTime(),
                order.getCloseTime(), order.getRemark(), order.getCreateTime(), order.getUpdateTime(),
                ActionsSupport.workOrder(order.getStatus()));
    }

    private AdminViews.AlarmBriefVO toAlarmBrief(AlarmEntity alarm) {
        if (alarm == null) {
            return null;
        }
        return new AdminViews.AlarmBriefVO(alarm.getId(), alarm.getAlarmType(), alarm.getDeviceType(),
                alarm.getDeviceNo(), alarm.getContent(), alarm.getHandled(), alarm.getCreateTime());
    }

    // ---------- 柜列表 ----------

    /**
     * 柜列表视图（S8 批次31）：柜详情此前只能从工单/订单钻取，运营要先"逛一遍柜"再钻取。
     * 直接复用 {@code AssetAdminService.pageCabinets}（已含站点范围过滤与 secret 脱敏——
     * 脱敏是 S5 云部署冒烟暴露的缺陷修复，不在本层重复实现），只做 Entity → VO 的字段收口。
     */
    public PageResult<AdminViews.CabinetVO> cabinetPage(Integer page, Integer limit, Long stationId,
                                                        Integer status, String cabinetNo) {
        PageResult<CabinetEntity> raw = assetAdminService.pageCabinets(page, limit, stationId, status, cabinetNo);
        List<AdminViews.CabinetVO> views = new ArrayList<>(raw.getList().size());
        long now = System.currentTimeMillis();
        for (CabinetEntity cabinet : raw.getList()) {
            views.add(toCabinetVO(cabinet, now));
        }
        return PageResult.of(views, raw.getTotal(), raw.getPage(), raw.getLimit());
    }

    private AdminViews.CabinetVO toCabinetVO(CabinetEntity cabinet, long now) {
        return new AdminViews.CabinetVO(cabinet.getId(), cabinet.getCabinetNo(), cabinet.getStationId(),
                cabinet.getCellCount(), cabinet.getStatus(), cabinet.getLastBootId(), cabinet.getLastEventSeq(),
                cabinet.getLastHeartbeatTime(),
                cabinet.getLastHeartbeatTime() == null ? null : now - cabinet.getLastHeartbeatTime());
    }

    // ---------- 柜详情 ----------

    /** 柜详情聚合：档案 + 仓与电池 + 未处理告警 + 进行中订单 + 最近指令流水（原本前端要串 5~6 个接口）。 */
    public AdminViews.CabinetDetailVO cabinetDetail(String cabinetNo) {
        CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                .eq(CabinetEntity::getCabinetNo, cabinetNo));
        if (cabinet == null) {
            throw new RRException("柜不存在: " + cabinetNo);
        }
        DataScopeSupport.requireStationAccess(cabinet.getStationId());

        List<CellEntity> cells = cellDao.selectList(new LambdaQueryWrapper<CellEntity>()
                .eq(CellEntity::getCabinetId, cabinet.getId())
                .orderByAsc(CellEntity::getCellNo));
        Map<Long, BatteryEntity> batteries = new LinkedHashMap<>();
        List<Long> batteryIds = cells.stream().map(CellEntity::getBatteryId)
                .filter(java.util.Objects::nonNull).toList();
        if (!batteryIds.isEmpty()) {
            for (BatteryEntity battery : batteryDao.selectBatchIds(batteryIds)) {
                batteries.put(battery.getId(), battery);
            }
        }
        List<AdminViews.CellVO> cellViews = new ArrayList<>(cells.size());
        for (CellEntity cell : cells) {
            BatteryEntity battery = cell.getBatteryId() == null ? null : batteries.get(cell.getBatteryId());
            cellViews.add(new AdminViews.CellVO(cell.getCellNo(), cell.getStatus(),
                    battery == null ? null : battery.getBatteryNo(),
                    battery == null ? null : battery.getSoc(), cell.getLockOrderId()));
        }

        List<AlarmEntity> alarms = alarmDao.selectList(new LambdaQueryWrapper<AlarmEntity>()
                .eq(AlarmEntity::getHandled, 0)
                .and(w -> w.eq(AlarmEntity::getDeviceNo, cabinetNo)
                        .or().likeRight(AlarmEntity::getDeviceNo, cabinetNo + "-"))
                .orderByDesc(AlarmEntity::getCreateTime)
                .last("LIMIT " + NESTED_LIMIT));
        List<AdminViews.AlarmBriefVO> alarmViews = new ArrayList<>(alarms.size());
        for (AlarmEntity alarm : alarms) {
            alarmViews.add(toAlarmBrief(alarm));
        }

        List<SwapOrderEntity> orders = swapOrderDao.selectList(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getCabinetId, cabinet.getId())
                .in(SwapOrderEntity::getStatus, ACTIVE_ORDER_STATUS)
                .orderByDesc(SwapOrderEntity::getCreateTime)
                .last("LIMIT " + NESTED_LIMIT));
        List<AdminViews.OrderBriefVO> orderViews = new ArrayList<>(orders.size());
        for (SwapOrderEntity order : orders) {
            orderViews.add(new AdminViews.OrderBriefVO(order.getOrderNo(), order.getOrderType(),
                    order.getStatus(), order.getFeeFen(), order.getCreateTime()));
        }

        List<CommandLogEntity> commands = commandLogDao.selectList(new LambdaQueryWrapper<CommandLogEntity>()
                .eq(CommandLogEntity::getCabinetNo, cabinetNo)
                .orderByDesc(CommandLogEntity::getId)
                .last("LIMIT " + NESTED_LIMIT));
        List<AdminViews.CommandVO> commandViews = new ArrayList<>(commands.size());
        for (CommandLogEntity command : commands) {
            commandViews.add(new AdminViews.CommandVO(command.getCommandAction(), command.getCommandSeq(),
                    command.getCommandStatus(), command.getRetryCount(), command.getTraceId(),
                    command.getCreateTime()));
        }

        long now = System.currentTimeMillis();
        AdminViews.CabinetVO cabinetVO = toCabinetVO(cabinet, now);
        return new AdminViews.CabinetDetailVO(cabinetVO, List.copyOf(cellViews), List.copyOf(alarmViews),
                List.copyOf(orderViews), List.copyOf(commandViews));
    }

    // ---------- 订单列表 ----------

    /**
     * 订单列表视图（S8 批次31）：柜号与可退金额各一次批量查询（不做 N+1），
     * 并给出每行的资金动作能力位（{@code refund}/{@code reversal} 由服务端二选一）。
     *
     * <p>可退金额走 {@code RefundService.refundableAmounts}——与订单详情同一个口径实现，
     * 避免"列表和详情显示两个数"。这里刻意<b>不</b>把 {@code idemKey} 等内部列带出去：
     * 域接口返回 Entity（含 idemKey），视图层收口的价值正在此。
     */
    public PageResult<AdminViews.OrderListItemVO> orderPage(Integer page, Integer limit, Long userId,
                                                            Integer status, String orderNo) {
        PageResult<SwapOrderEntity> raw = swapOrderService.pageOrders(page, limit, userId, status, orderNo);
        List<SwapOrderEntity> orders = raw.getList();
        Map<Long, String> cabinetNos = cabinetNosOf(orders);
        Map<Long, Integer> refundable = refundService.refundableAmounts(
                orders.stream().map(SwapOrderEntity::getId).toList());

        List<AdminViews.OrderListItemVO> views = new ArrayList<>(orders.size());
        for (SwapOrderEntity order : orders) {
            int refundableFen = refundable.getOrDefault(order.getId(), 0);
            views.add(new AdminViews.OrderListItemVO(order.getOrderNo(), order.getOrderType(), order.getUserId(),
                    order.getStationId(), order.getCabinetId() == null ? null : cabinetNos.get(order.getCabinetId()),
                    order.getStatus(), orderStatusDesc(order.getStatus()), order.getFeeFen(),
                    order.getDiscountFen(), order.getPayType(), order.getCreateTime(), order.getCompleteTime(),
                    refundableFen, ActionsSupport.order(order.getStatus(), refundableFen)));
        }
        return PageResult.of(views, raw.getTotal(), raw.getPage(), raw.getLimit());
    }

    /** 一页订单的柜号映射（一次 in 查询；柜被删仍可显示 null，不抛）。 */
    private Map<Long, String> cabinetNosOf(List<SwapOrderEntity> orders) {
        List<Long> cabinetIds = orders.stream().map(SwapOrderEntity::getCabinetId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> cabinetNos = new LinkedHashMap<>();
        if (!cabinetIds.isEmpty()) {
            for (CabinetEntity cabinet : cabinetDao.selectBatchIds(cabinetIds)) {
                cabinetNos.put(cabinet.getId(), cabinet.getCabinetNo());
            }
        }
        return cabinetNos;
    }

    // ---------- 订单详情 ----------

    public AdminViews.OrderDetailVO orderDetail(String orderNo) {
        SwapOrderEntity order = swapOrderDao.selectOne(new LambdaQueryWrapper<SwapOrderEntity>()
                .eq(SwapOrderEntity::getOrderNo, orderNo));
        if (order == null) {
            throw new RRException("订单不存在: " + orderNo);
        }
        DataScopeSupport.requireStationAccess(order.getStationId());

        CabinetEntity cabinet = order.getCabinetId() == null ? null : cabinetDao.selectById(order.getCabinetId());
        CellEntity cell = order.getCellId() == null ? null : cellDao.selectById(order.getCellId());
        BatteryEntity take = order.getTakeBatteryId() == null ? null
                : batteryDao.selectById(order.getTakeBatteryId());
        BatteryEntity returned = order.getReturnBatteryId() == null ? null
                : batteryDao.selectById(order.getReturnBatteryId());

        List<PaymentRecordEntity> payments = paymentRecordDao.selectList(
                new LambdaQueryWrapper<PaymentRecordEntity>()
                        .eq(PaymentRecordEntity::getOrderId, order.getId())
                        .orderByAsc(PaymentRecordEntity::getId));
        List<AdminViews.PaymentVO> paymentViews = new ArrayList<>(payments.size());
        for (PaymentRecordEntity payment : payments) {
            paymentViews.add(new AdminViews.PaymentVO(payment.getTradeNo(), payment.getAmountFen(),
                    payment.getPaymentType(), payment.getStatus(), payment.getCreateTime()));
        }
        List<RefundRecordEntity> refunds = refundRecordDao.selectList(
                new LambdaQueryWrapper<RefundRecordEntity>()
                        .eq(RefundRecordEntity::getOrderId, order.getId())
                        .orderByAsc(RefundRecordEntity::getId));
        List<AdminViews.RefundVO> refundViews = new ArrayList<>(refunds.size());
        for (RefundRecordEntity refund : refunds) {
            refundViews.add(new AdminViews.RefundVO(refund.getRefundNo(), refund.getAmountFen(),
                    refund.getReason(), refund.getStatus(), refund.getCreateTime()));
        }

        int refundableFen = refundService.refundableAmount(order.getId());
        return new AdminViews.OrderDetailVO(order.getOrderNo(), order.getOrderType(), order.getUserId(),
                order.getStationId(), cabinet == null ? null : cabinet.getCabinetNo(),
                cell == null ? null : cell.getCellNo(), take == null ? null : take.getBatteryNo(),
                returned == null ? null : returned.getBatteryNo(), order.getStatus(),
                orderStatusDesc(order.getStatus()), order.getFeeFen(), order.getDiscountFen(), order.getPayType(),
                order.getPreemptExpireTime(), order.getCreateTime(), order.getOpenTime(), order.getTakeTime(),
                order.getReturnTime(), order.getCompleteTime(), order.getCancelTime(), order.getCloseReason(),
                List.copyOf(paymentViews), List.copyOf(refundViews), refundableFen,
                ActionsSupport.order(order.getStatus(), refundableFen));
    }

    /** 状态描述：脏数据不抛（页面不应因一条异常码崩），未知码回退为原始值字符串。 */
    private String orderStatusDesc(Integer status) {
        if (status == null) {
            return null;
        }
        try {
            return OrderStatus.fromCode(status).name();
        } catch (RuntimeException e) {
            return String.valueOf(status);
        }
    }
}
