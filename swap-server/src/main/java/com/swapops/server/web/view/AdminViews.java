package com.swapops.server.web.view;

import java.util.List;

/**
 * BFF 视图对象集合（S8 批次29）：面向页面的只读聚合契约。
 *
 * <p>与 Entity 直出的区别（本包存在的理由）：
 * <ul>
 *   <li><b>不含敏感与内部列</b>：柜密钥、雪花 idem_key 之类不外泄（{@code CabinetVO} 有意不列 secret）；</li>
 *   <li><b>带 {@code allowedActions}</b>：由 {@code ActionsSupport} 按状态算出，前端不复制状态机；</li>
 *   <li><b>一次请求一页数据</b>：页面不再串 5~6 个接口，字段名由本契约固化（OpenAPI 可生成 TS 类型）。</li>
 * </ul>
 * 集中在一个文件内声明：视图对象无行为、彼此常组合使用，拆成十余个单文件反而降低可读性。
 */
public final class AdminViews {

    private AdminViews() {
    }

    /** 看板（口径同 DashboardService；{@code dataScoped=true} 表示本次为"本域口径"直算结果）。 */
    public record DashboardVO(long generatedAt, long totalBatteries, long fullBatteries, double fullBatteryRate,
                              long totalStations, long availableStations, double stationAvailabilityRate,
                              long completedToday, double turnoverRate, List<String> unavailableStations,
                              boolean dataScoped) {
    }

    /** 告警条目：附带"是否已有工单"与可执行动作（{@code create-work-order} 仅在尚无工单且未处理时给出）。 */
    public record AlarmItemVO(Long id, String deviceType, String deviceNo, String alarmType, String content,
                              Integer handled, Long handler, Long createTime, Long handledTime,
                              Long workOrderId, List<String> allowedActions) {
    }

    /** 告警摘要（嵌在建议单/工单详情里，避免前端再查一次告警接口）。 */
    public record AlarmBriefVO(Long id, String alarmType, String deviceType, String deviceNo,
                               String content, Integer handled, Long createTime) {
    }

    /** Agent 建议单（S6 闭环的人工处置视图）；{@code alarmId}/{@code alarmType} 由 params_json 解析带出。 */
    public record SuggestionVO(Long id, String actionNo, String actionType, String idemKey, String reason,
                               Integer status, String proposer, String confirmer, Long confirmTime,
                               String resultJson, String errorMsg, Long createTime,
                               Long alarmId, String alarmType, List<String> allowedActions) {
    }

    public record WorkOrderVO(Long id, String woNo, Long alarmId, String source, Long reporterUserId,
                              String description, String deviceType, String deviceNo, Long stationId,
                              String title, String severity, Integer status, Long handlerId, Long slaDeadline,
                              Integer slaBreached, Long verifyTime, Long closeTime, String remark,
                              Long createTime, Long updateTime, List<String> allowedActions) {
    }

    public record WorkOrderLogVO(String action, Integer fromStatus, Integer toStatus, String operator,
                                 String remark, Long createTime) {
    }

    public record WorkOrderDetailVO(WorkOrderVO order, List<WorkOrderLogVO> logs, AlarmBriefVO alarm) {
    }

    /** 柜档案（<b>不含密钥</b>）：{@code heartbeatAgeMs} 由服务端算好，前端不做时间差计算。 */
    public record CabinetVO(Long id, String cabinetNo, Long stationId, Integer cellCount, Integer status,
                            String lastBootId, Long lastEventSeq, Long lastHeartbeatTime, Long heartbeatAgeMs) {
    }

    public record CellVO(Integer cellNo, Integer status, String batteryNo, Integer soc, Long lockOrderId) {
    }

    public record CommandVO(String commandAction, Long commandSeq, Integer commandStatus, Integer retryCount,
                            String traceId, Long createTime) {
    }

    public record OrderBriefVO(String orderNo, String orderType, Integer status, Integer feeFen, Long createTime) {
    }

    /** 柜详情聚合：档案 + 仓与电池 + 未处理告警 + 进行中订单 + 最近指令流水（原本要串 5~6 个接口）。 */
    public record CabinetDetailVO(CabinetVO cabinet, List<CellVO> cells, List<AlarmBriefVO> openAlarms,
                                  List<OrderBriefVO> activeOrders, List<CommandVO> recentCommands) {
    }

    /** 支付流水（{@code status}：1 成功 / 2 失败，与 payment_record 同型）。 */
    public record PaymentVO(String tradeNo, Integer amountFen, String paymentType, Integer status,
                            Long createTime) {
    }

    public record RefundVO(String refundNo, Integer amountFen, String reason, String status, Long createTime) {
    }

    /**
     * 订单详情：基础字段 + 时间线 + 支付/退款流水 + 可退金额。
     * {@code refundableFen} 由资金口径给出，前端据此决定是否显示退款入口（<b>不由前端推算可退额</b>——
     * 押金双退事故的根因就是"可退额度由流水反推"，口径必须留在服务端）。
     */
    public record OrderDetailVO(String orderNo, String orderType, Long userId, Long stationId,
                                String cabinetNo, Integer cellNo, String takeBatteryNo, String returnBatteryNo,
                                Integer status, String statusDesc, Integer feeFen, Integer discountFen,
                                String payType, Long preemptExpireTime, Long createTime, Long openTime,
                                Long takeTime, Long returnTime, Long completeTime, Long cancelTime,
                                String closeReason, List<PaymentVO> payments, List<RefundVO> refunds,
                                int refundableFen) {
    }
}
