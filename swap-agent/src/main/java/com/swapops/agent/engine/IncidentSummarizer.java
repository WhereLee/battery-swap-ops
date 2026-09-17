package com.swapops.agent.engine;

import com.swapops.agent.model.AlarmView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事故摘要器：把窗口内一批告警按设备聚合成"运维读得懂的一段话"（时间跨度 + 类型分布 + 计数）。
 * 纯函数（now 由调用方传入），便于测试与评测。
 */
@Component
public class IncidentSummarizer {

    /** 单设备事故摘要（窗口内未处理告警的聚合视图）。 */
    public record DeviceIncident(String deviceNo, int count, Map<String, Integer> byType,
                                 long firstTime, long lastTime) {
    }

    /** 窗口内（createTime >= now - windowMs）按设备聚合；排序：计数降序 → 首现时间升序。 */
    public List<DeviceIncident> summarize(List<AlarmView> alarms, long now, long windowMs) {
        Map<String, List<AlarmView>> byDevice = new LinkedHashMap<>();
        if (alarms != null) {
            for (AlarmView alarm : alarms) {
                if (alarm == null || alarm.handled() == null || alarm.handled() != 0) {
                    continue;
                }
                long created = alarm.createTime() == null ? 0L : alarm.createTime();
                if (created < now - windowMs) {
                    continue;
                }
                byDevice.computeIfAbsent(alarm.deviceNo() == null ? "-" : alarm.deviceNo(),
                        k -> new ArrayList<>()).add(alarm);
            }
        }
        List<DeviceIncident> incidents = new ArrayList<>();
        for (Map.Entry<String, List<AlarmView>> entry : byDevice.entrySet()) {
            List<AlarmView> list = entry.getValue();
            Map<String, Integer> byType = new LinkedHashMap<>();
            long first = Long.MAX_VALUE;
            long last = Long.MIN_VALUE;
            for (AlarmView alarm : list) {
                byType.merge(alarm.alarmType() == null ? "-" : alarm.alarmType(), 1, Integer::sum);
                long created = alarm.createTime() == null ? 0L : alarm.createTime();
                first = Math.min(first, created);
                last = Math.max(last, created);
            }
            incidents.add(new DeviceIncident(entry.getKey(), list.size(), byType, first, last));
        }
        incidents.sort(Comparator.comparingInt(DeviceIncident::count).reversed()
                .thenComparingLong(DeviceIncident::firstTime));
        return incidents;
    }

    /** 渲染为一段话："SWAP-C-005 未处理 3 条（CABINET_FAULT x2、OFFLINE x1），首现 34m 前，最近 12m 前"。 */
    public String render(DeviceIncident incident, long now) {
        StringBuilder types = new StringBuilder();
        for (Map.Entry<String, Integer> entry : incident.byType().entrySet()) {
            if (types.length() > 0) {
                types.append("、");
            }
            types.append(entry.getKey()).append(" x").append(entry.getValue());
        }
        return incident.deviceNo() + " 未处理 " + incident.count() + " 条（" + types + "）"
                + "，首现 " + ageText(incident.firstTime(), now) + "，最近 " + ageText(incident.lastTime(), now);
    }

    /** 相对时间文案：<60s "just now"；<60m "Nm"；<48h "Hh Mm"；其余 "Nd Hh"。 */
    static String ageText(long ts, long now) {
        long seconds = Math.max(0, (now - ts) / 1000);
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + "h" + (minutes % 60) + "m";
        }
        return (hours / 24) + "d" + (hours % 24) + "h";
    }
}
