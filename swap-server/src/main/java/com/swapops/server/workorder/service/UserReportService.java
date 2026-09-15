package com.swapops.server.workorder.service;

import com.swapops.server.device.config.SwapRedisKeys;
import com.swapops.server.workorder.entity.WorkOrderEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 用户报障入口（S7 WP-D）：同用户同柜 10 分钟窗口去重（Redis；近重返回既有工单，幂等）。
 * Redis 故障 fail-open（去重失败不阻断报障——报障是用户生命线）。
 */
@Slf4j
@Service
public class UserReportService {

    private static final Duration DEDUP_TTL = Duration.ofMinutes(10);

    private final WorkOrderService workOrderService;
    private final StringRedisTemplate stringRedisTemplate;

    public UserReportService(WorkOrderService workOrderService, StringRedisTemplate stringRedisTemplate) {
        this.workOrderService = workOrderService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public WorkOrderEntity report(Long userId, String cabinetNo, Integer cellNo,
                                  String type, String description) {
        String key = SwapRedisKeys.USER_REPORT_DEDUP_PREFIX + userId + ":" + cabinetNo;
        try {
            String existingWoNo = stringRedisTemplate.opsForValue().get(key);
            if (existingWoNo != null) {
                WorkOrderEntity existing = workOrderService.byWoNo(existingWoNo);
                if (existing != null) {
                    log.info("[报障] 窗口内近重，返回既有工单 userId={} cabinetNo={} woNo={}",
                            userId, cabinetNo, existingWoNo);
                    return existing;
                }
            }
        } catch (RuntimeException e) {
            log.warn("[报障] 去重检查异常（fail-open） userId={} cause={}", userId, e.getMessage());
        }
        WorkOrderEntity created = workOrderService.createFromUserReport(userId, cabinetNo, cellNo, type, description);
        try {
            stringRedisTemplate.opsForValue().set(key, created.getWoNo(), DEDUP_TTL);
        } catch (RuntimeException e) {
            log.warn("[报障] 去重键写入异常（忽略） woNo={} cause={}", created.getWoNo(), e.getMessage());
        }
        return created;
    }
}
