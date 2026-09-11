package com.swapops.server.device.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 柜代际重放守卫（S3.1）：补序守卫的跨代际盲区。
 *
 * <p>序守卫只守"同 bootId 单调递增"，对 bootId 变化一律接受并重置基线——一份被截获的旧代际事件
 * 重放时（或设备重启后才送达的迟到重投）会被当"新代际"接受，导致台账回退、极端下误销在途流水。
 * 本守卫维持"已见代际集合"：进程启动 UUID（bootId）不复用，历史命中=重放。</p>
 *
 * <p>分工：DB 序守卫仍是权威（同代际乱序/重复）；本守卫只在"代际切换"这一稀有时刻介入——
 * 与当前代际一致时一次 GET 直通（稳态零额外代价）；Redis 异常 fail-open（放行+WARN，降级为既有行为，
 * 不阻断设备上报生命线）。Redis 数据丢失后守卫失效（与 seq 回退同一事故面，由启动对齐/对账兜底，
 * 如实声明）。</p>
 */
@Slf4j
@Component
public class DeviceBootGenerationGuard {

    /** 历史代际保留（重启代际只增不减，30 天覆盖迟到重投/重放现实窗口） */
    private static final long HISTORY_TTL_DAYS = 30;

    private final StringRedisTemplate stringRedisTemplate;

    public DeviceBootGenerationGuard(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 是否"已见代际的重放"：
     * 与当前代际一致（稳态）→ false（直通，不查历史）；
     * 换代际且历史命中 → true（重放，调用方拒绝）；
     * 换代际但历史未见 → false（真重启新代际，调用方接受后 register）；
     * Redis 异常 → false（fail-open 降级 + WARN）。
     */
    public boolean isReplay(String cabinetNo, String bootId) {
        try {
            String current = stringRedisTemplate.opsForValue()
                    .get(SwapRedisKeys.BOOT_CURRENT_PREFIX + cabinetNo);
            if (current == null || current.equals(bootId)) {
                return false;
            }
            return Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                    .isMember(SwapRedisKeys.BOOT_HISTORY_PREFIX + cabinetNo, bootId));
        } catch (RuntimeException e) {
            log.warn("代际重放判定异常，降级放行 cabinetNo={} bootId={} cause={}", cabinetNo, bootId, e.getMessage());
            return false;
        }
    }

    /**
     * 事件被序守卫接受后登记代际（幂等）：current=bootId + 历史 SADD（TTL 仅首写）。
     * 登记失败仅 WARN：守卫是加固层不是主链路，台账已按 DB 序守卫正确推进。
     */
    public void register(String cabinetNo, String bootId) {
        try {
            stringRedisTemplate.opsForValue().set(SwapRedisKeys.BOOT_CURRENT_PREFIX + cabinetNo, bootId);
            Long added = stringRedisTemplate.opsForSet()
                    .add(SwapRedisKeys.BOOT_HISTORY_PREFIX + cabinetNo, bootId);
            if (added != null && added > 0) {
                stringRedisTemplate.expire(SwapRedisKeys.BOOT_HISTORY_PREFIX + cabinetNo,
                        HISTORY_TTL_DAYS, TimeUnit.DAYS);
            }
        } catch (RuntimeException e) {
            log.warn("代际登记失败（守卫降级为无历史，DB 序守卫仍权威）cabinetNo={} bootId={} cause={}",
                    cabinetNo, bootId, e.getMessage());
        }
    }
}
