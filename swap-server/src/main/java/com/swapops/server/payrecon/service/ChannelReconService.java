package com.swapops.server.payrecon.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.alarm.AlarmType;
import com.swapops.server.alarm.service.AlarmService;
import com.swapops.server.admin.security.AdminContext;
import com.swapops.server.common.RRException;
import com.swapops.server.order.dao.PayOrderDao;
import com.swapops.server.order.entity.PayOrderEntity;
import com.swapops.server.order.enums.PayOrderStatus;
import com.swapops.server.payrecon.dao.ChannelBillDao;
import com.swapops.server.payrecon.dao.ReconDiffDao;
import com.swapops.server.payrecon.entity.ChannelBillEntity;
import com.swapops.server.payrecon.entity.ReconDiffEntity;
import com.swapops.server.payrecon.enums.ReconDiffStatus;
import com.swapops.server.payrecon.enums.ReconDiffType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 渠道对账（S7 WP-C）：
 * <ul>
 *   <li>账单导出（mock，dev）：按日从 pay_order（非 WAIT）生成 CSV，支持差异注入（missing/extra/amount/status）供剧本/演示；</li>
 *   <li>导入：先清当日同 channel 再插入（幂等覆盖），随后触发对账；</li>
 *   <li>对账：渠道账单 ↔ 平台支付单双向比对，四类差异；**OPEN 差异按重算结果重建，HANDLED/IGNORED 保留留痕**；</li>
 *   <li>差异 >0 告警 CHANNEL_RECON_DIFF（归零自动关）。</li>
 * </ul>
 */
@Slf4j
@Service
public class ChannelReconService {

    public static final String DEFAULT_CHANNEL = "MOCK";
    private static final String RECON_DEVICE = "channel-recon";

    private final ChannelBillDao channelBillDao;
    private final ReconDiffDao reconDiffDao;
    private final PayOrderDao payOrderDao;
    private final AlarmService alarmService;

    public ChannelReconService(ChannelBillDao channelBillDao, ReconDiffDao reconDiffDao,
                               PayOrderDao payOrderDao, AlarmService alarmService) {
        this.channelBillDao = channelBillDao;
        this.reconDiffDao = reconDiffDao;
        this.payOrderDao = payOrderDao;
        this.alarmService = alarmService;
    }

    /** 对账汇总（导入/手动触发返回） */
    public record ReconResult(String billDate, String channel, int billRows, int platformRows, int diffs) {
    }

    // ---------- 账单导出（mock，dev 专用调用） ----------

    /**
     * 生成 CSV（header: bill_date,trade_no,amount_fen,status）；anomaly 支持逗号分隔的多个注入：
     * missing（删一笔成功）/ extra（加一笔渠道独有）/ amount（改一笔金额）/ status（改一笔状态）。
     */
    public String exportBillCsv(String date, String anomaly) {
        List<PayOrderEntity> orders = platformOrders(date, DEFAULT_CHANNEL);
        List<String[]> rows = new ArrayList<>();
        for (PayOrderEntity order : orders) {
            rows.add(new String[]{date, order.getTradeNo(), String.valueOf(order.getAmountFen()), order.getStatus()});
        }
        Set<String> anomalies = new LinkedHashSet<>();
        if (anomaly != null && !anomaly.isBlank()) {
            for (String part : anomaly.split(",")) {
                anomalies.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (anomalies.contains("missing")) {
            rows.removeIf(row -> PayOrderStatus.SUCCESS.name().equals(row[3]));
        }
        if (anomalies.contains("amount") && !rows.isEmpty()) {
            rows.get(0)[2] = String.valueOf(Integer.parseInt(rows.get(0)[2]) + 100);
        }
        if (anomalies.contains("status") && !rows.isEmpty()) {
            rows.get(0)[3] = PayOrderStatus.SUCCESS.name().equals(rows.get(0)[3])
                    ? PayOrderStatus.CLOSED.name() : PayOrderStatus.SUCCESS.name();
        }
        if (anomalies.contains("extra")) {
            rows.add(new String[]{date, "RFAKE" + System.currentTimeMillis(), "1234", PayOrderStatus.SUCCESS.name()});
        }
        StringBuilder sb = new StringBuilder("bill_date,trade_no,amount_fen,status\n");
        for (String[] row : rows) {
            sb.append(String.join(",", row)).append('\n');
        }
        return sb.toString();
    }

    // ---------- 导入 + 对账 ----------

    /** 导入账单（幂等覆盖）并对账；CSV 行须与 date/channel 一致 */
    @Transactional
    public ReconResult importBill(String date, String channel, String csv) {
        String ch = channel == null || channel.isBlank() ? DEFAULT_CHANNEL : channel.trim();
        List<String[]> parsed = parseCsv(csv);
        long now = System.currentTimeMillis();
        channelBillDao.delete(new LambdaQueryWrapper<ChannelBillEntity>()
                .eq(ChannelBillEntity::getBillDate, date)
                .eq(ChannelBillEntity::getChannel, ch));
        for (String[] row : parsed) {
            if (!date.equals(row[0])) {
                throw new RRException("账单行日期与导入日期不一致: " + row[0] + " != " + date);
            }
            ChannelBillEntity bill = new ChannelBillEntity();
            bill.setBillDate(row[0]);
            bill.setChannel(ch);
            bill.setTradeNo(row[1]);
            try {
                bill.setAmountFen(Integer.parseInt(row[2]));
            } catch (NumberFormatException e) {
                throw new RRException("账单金额非法: " + row[2]);
            }
            String status = row[3].trim().toUpperCase(Locale.ROOT);
            if (!PayOrderStatus.SUCCESS.name().equals(status) && !PayOrderStatus.CLOSED.name().equals(status)) {
                throw new RRException("账单状态非法（SUCCESS/CLOSED）: " + row[3]);
            }
            bill.setStatus(status);
            bill.setImportedAt(now);
            channelBillDao.insert(bill);
        }
        log.info("[渠道对账] 账单导入 date={} channel={} rows={}", date, ch, parsed.size());
        return reconcile(date, ch);
    }

    /**
     * 对账（幂等重建）：当日同 channel 的 OPEN 差异删除并按重算结果重建；
     * 已 HANDLED/IGNORED 的差异键不重建（保留处置留痕，防"账单已平仍告警"）。
     */
    @Transactional
    public ReconResult reconcile(String date, String channel) {
        String ch = channel == null || channel.isBlank() ? DEFAULT_CHANNEL : channel.trim();
        List<ChannelBillEntity> bills = channelBillDao.selectList(new LambdaQueryWrapper<ChannelBillEntity>()
                .eq(ChannelBillEntity::getBillDate, date)
                .eq(ChannelBillEntity::getChannel, ch));
        List<PayOrderEntity> orders = platformOrders(date, ch);

        Map<String, ChannelBillEntity> billByTrade = new HashMap<>();
        for (ChannelBillEntity bill : bills) {
            billByTrade.put(bill.getTradeNo(), bill);
        }
        Map<String, PayOrderEntity> orderByTrade = new HashMap<>();
        for (PayOrderEntity order : orders) {
            orderByTrade.put(order.getTradeNo(), order);
        }

        List<DiffDraft> computed = new ArrayList<>();
        for (ChannelBillEntity bill : bills) {
            PayOrderEntity order = orderByTrade.get(bill.getTradeNo());
            if (order == null) {
                computed.add(new DiffDraft(bill.getTradeNo(), ReconDiffType.CHANNEL_ONLY,
                        "渠道有平台无 金额=" + bill.getAmountFen() + " 状态=" + bill.getStatus()));
                continue;
            }
            if (bill.getAmountFen() != null && order.getAmountFen() != null
                    && !bill.getAmountFen().equals(order.getAmountFen())) {
                computed.add(new DiffDraft(bill.getTradeNo(), ReconDiffType.AMOUNT_MISMATCH,
                        "渠道=" + bill.getAmountFen() + " 平台=" + order.getAmountFen()));
            }
            String platformStatus = order.getStatus() == null ? "" : order.getStatus().toUpperCase(Locale.ROOT);
            if (!bill.getStatus().equals(platformStatus)) {
                computed.add(new DiffDraft(bill.getTradeNo(), ReconDiffType.STATUS_MISMATCH,
                        "渠道=" + bill.getStatus() + " 平台=" + platformStatus));
            }
        }
        for (PayOrderEntity order : orders) {
            if (!billByTrade.containsKey(order.getTradeNo())) {
                computed.add(new DiffDraft(order.getTradeNo(), ReconDiffType.PLATFORM_ONLY,
                        "平台有渠道无 金额=" + order.getAmountFen() + " 状态=" + order.getStatus()));
            }
        }

        Set<String> handledKeys = new LinkedHashSet<>();
        List<ReconDiffEntity> handled = reconDiffDao.selectList(new LambdaQueryWrapper<ReconDiffEntity>()
                .eq(ReconDiffEntity::getBillDate, date)
                .eq(ReconDiffEntity::getChannel, ch)
                .ne(ReconDiffEntity::getStatus, ReconDiffStatus.OPEN.name()));
        for (ReconDiffEntity diff : handled) {
            handledKeys.add(diffKey(diff.getTradeNo(), diff.getDiffType()));
        }
        reconDiffDao.delete(new LambdaQueryWrapper<ReconDiffEntity>()
                .eq(ReconDiffEntity::getBillDate, date)
                .eq(ReconDiffEntity::getChannel, ch)
                .eq(ReconDiffEntity::getStatus, ReconDiffStatus.OPEN.name()));

        long now = System.currentTimeMillis();
        int persisted = 0;
        for (DiffDraft draft : computed) {
            if (handledKeys.contains(diffKey(draft.tradeNo(), draft.type().name()))) {
                continue;
            }
            ReconDiffEntity entity = new ReconDiffEntity();
            entity.setBillDate(date);
            entity.setChannel(ch);
            entity.setTradeNo(draft.tradeNo());
            entity.setDiffType(draft.type().name());
            entity.setDetail(truncate(draft.detail()));
            entity.setStatus(ReconDiffStatus.OPEN.name());
            entity.setCreateTime(now);
            reconDiffDao.insert(entity);
            persisted++;
        }
        if (persisted > 0) {
            alarmService.raise(AlarmService.DEVICE_SYSTEM, RECON_DEVICE, AlarmType.CHANNEL_RECON_DIFF,
                    "渠道对账差异 date=" + date + " channel=" + ch + " diff=" + persisted + " " + summarize(computed));
            log.error("[渠道对账] 差异 date={} channel={} diff={} {}", date, ch, persisted, summarize(computed));
        } else {
            alarmService.markRecovered(AlarmService.DEVICE_SYSTEM, RECON_DEVICE, AlarmType.CHANNEL_RECON_DIFF);
            log.info("[渠道对账] 零差异 date={} channel={} bills={} platform={}", date, ch, bills.size(), orders.size());
        }
        return new ReconResult(date, ch, bills.size(), orders.size(), persisted);
    }

    // ---------- 查询与处置 ----------

    public List<ReconDiffEntity> listDiffs(String date, String channel, String status) {
        return reconDiffDao.selectList(new LambdaQueryWrapper<ReconDiffEntity>()
                .eq(date != null && !date.isBlank(), ReconDiffEntity::getBillDate, date)
                .eq(channel != null && !channel.isBlank(), ReconDiffEntity::getChannel, channel)
                .eq(status != null && !status.isBlank(), ReconDiffEntity::getStatus, status)
                .orderByDesc(ReconDiffEntity::getId)
                .last("LIMIT 200"));
    }

    public Map<String, Object> report(String date, String channel) {
        String ch = channel == null || channel.isBlank() ? DEFAULT_CHANNEL : channel.trim();
        List<ReconDiffEntity> diffs = reconDiffDao.selectList(new LambdaQueryWrapper<ReconDiffEntity>()
                .eq(ReconDiffEntity::getBillDate, date)
                .eq(ReconDiffEntity::getChannel, ch));
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (ReconDiffType type : ReconDiffType.values()) {
            byType.put(type.name(), 0);
        }
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (ReconDiffStatus status : ReconDiffStatus.values()) {
            byStatus.put(status.name(), 0);
        }
        for (ReconDiffEntity diff : diffs) {
            byType.merge(diff.getDiffType(), 1, Integer::sum);
            byStatus.merge(diff.getStatus(), 1, Integer::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("billDate", date);
        result.put("channel", ch);
        result.put("bills", channelBillDao.selectCount(new LambdaQueryWrapper<ChannelBillEntity>()
                .eq(ChannelBillEntity::getBillDate, date).eq(ChannelBillEntity::getChannel, ch)));
        result.put("platformOrders", platformOrders(date, ch).size());
        result.put("totalDiffs", diffs.size());
        result.put("openDiffs", byStatus.getOrDefault(ReconDiffStatus.OPEN.name(), 0));
        result.put("byType", byType);
        result.put("byStatus", byStatus);
        return result;
    }

    /** 差异处置（CAS OPEN→HANDLED/IGNORED，操作人取管理端上下文） */
    public boolean handle(Long diffId, String status, String remark) {
        String target = status == null || status.isBlank() ? ReconDiffStatus.HANDLED.name()
                : status.trim().toUpperCase(Locale.ROOT);
        if (!ReconDiffStatus.HANDLED.name().equals(target) && !ReconDiffStatus.IGNORED.name().equals(target)) {
            throw new RRException("处置状态可选 HANDLED / IGNORED");
        }
        int rows = reconDiffDao.update(null, new LambdaUpdateWrapper<ReconDiffEntity>()
                .eq(ReconDiffEntity::getId, diffId)
                .eq(ReconDiffEntity::getStatus, ReconDiffStatus.OPEN.name())
                .set(ReconDiffEntity::getStatus, target)
                .set(ReconDiffEntity::getHandledBy, AdminContext.currentUsernameOr("admin"))
                .set(ReconDiffEntity::getHandledTime, System.currentTimeMillis())
                .set(ReconDiffEntity::getRemark, truncate(remark)));
        if (rows == 0) {
            throw new RRException("差异单不存在或已被处置: " + diffId);
        }
        log.info("[渠道对账] 差异已处置 id={} status={} by={}", diffId, target,
                AdminContext.currentUsernameOr("admin"));
        return true;
    }

    // ---------- 内部 ----------

    /** 平台侧参与对账的支付单：非 WAIT 且 callback_time 在账单日内 */
    private List<PayOrderEntity> platformOrders(String date, String channel) {
        long start = LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = start + 24L * 3600 * 1000;
        return payOrderDao.selectList(new LambdaQueryWrapper<PayOrderEntity>()
                .eq(PayOrderEntity::getChannel, channel)
                .ne(PayOrderEntity::getStatus, PayOrderStatus.WAIT.name())
                .ge(PayOrderEntity::getCallbackTime, start)
                .lt(PayOrderEntity::getCallbackTime, end)
                .orderByAsc(PayOrderEntity::getId));
    }

    /** CSV 解析：跳过表头/空行；每行 4 列（bill_date,trade_no,amount_fen,status） */
    List<String[]> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new RRException("账单内容为空");
        }
        List<String[]> rows = new ArrayList<>();
        String[] lines = csv.split("\r?\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("bill_date")) {
                continue;
            }
            String[] parts = trimmed.split(",");
            if (parts.length != 4) {
                throw new RRException("账单行格式非法（需 4 列 bill_date,trade_no,amount_fen,status）: " + trimmed);
            }
            for (int i = 0; i < parts.length; i++) {
                parts[i] = parts[i].trim();
            }
            if (parts[0].isEmpty() || parts[1].isEmpty()) {
                throw new RRException("账单行缺日期/流水号: " + trimmed);
            }
            rows.add(parts);
        }
        if (rows.isEmpty()) {
            throw new RRException("账单无有效数据行");
        }
        return rows;
    }

    private String diffKey(String tradeNo, String type) {
        return tradeNo + "|" + type;
    }

    private String summarize(List<DiffDraft> drafts) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DiffDraft draft : drafts) {
            counts.merge(draft.type().name(), 1, Integer::sum);
        }
        return counts.toString();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private record DiffDraft(String tradeNo, ReconDiffType type, String detail) {
    }
}
