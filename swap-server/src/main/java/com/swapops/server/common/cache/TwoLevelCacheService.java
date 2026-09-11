package com.swapops.server.common.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.server.config.CacheProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 两级缓存（S3.8 WP1，标准实现）：
 * <ul>
 *   <li>读：L1(Caffeine) → L2(Redis JSON) → 回源；回填两级；空值短 TTL 缓存（防穿透）；</li>
 *   <li>击穿：进程内 per-key 锁单飞 + Redis SETNX 跨实例重建锁（未抢到等 200ms 重读 L2，再降级回源）；</li>
 *   <li>雪崩：L2 TTL 加随机抖动（不同键错峰过期）；</li>
 *   <li>一致性：写路径 evict=删 L2 + 本地失效 + Pub/Sub 广播（丢消息由 L1 TTL 兜底）；</li>
 *   <li>降级：Redis 异常时不抛错——跳过 L2 直读 DB（读路径 fail-open，只降性能不降正确性）。</li>
 * </ul>
 * 红线：仅用于展示/字典类读；资金、库存、订单状态禁止经此缓存（见 S3.8 方案 §四）。
 */
@Slf4j
@Service
public class TwoLevelCacheService {

    private final StringRedisTemplate redis;
    private final CacheProperties properties;
    private final LocalCacheInvalidator localCache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** per-key 本地单飞锁（键空间小，常驻不清理；避免"删锁竞态"） */
    private final ConcurrentHashMap<String, Object> localLocks = new ConcurrentHashMap<>();

    /** 延迟双删调度（守护线程；二删只补删 L2，防旧值读回填） */
    private final ScheduledExecutorService delayedEvictExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "swap-cache-delayed-evict");
        t.setDaemon(true);
        return t;
    });

    private final LongAdder l1Hits = new LongAdder();
    private final LongAdder l2Hits = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> rebuildsByKey = new ConcurrentHashMap<>();

    public TwoLevelCacheService(StringRedisTemplate redis, CacheProperties properties,
                                LocalCacheInvalidator localCache) {
        this.redis = redis;
        this.properties = properties;
        this.localCache = localCache;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /** 单对象缓存读（类型化） */
    public <T> T getEntity(String key, Class<T> type, Supplier<T> loader) {
        return get(key, objectMapper.getTypeFactory().constructType(type), loader);
    }

    /** 列表缓存读（类型化元素） */
    public <T> List<T> getList(String key, Class<T> elementType, Supplier<List<T>> loader) {
        return get(key, objectMapper.getTypeFactory().constructCollectionType(List.class, elementType), loader);
    }

    @SuppressWarnings("unchecked")
    private <T> T get(String key, JavaType type, Supplier<T> loader) {
        if (!properties.isEnabled()) {
            return loader.get();
        }
        Object local = localCache.getIfPresent(key);
        if (local != null) {
            l1Hits.increment();
            return local == LocalCacheInvalidator.NULL ? null : (T) local;
        }
        String cached = l2Get(key);
        if (cached != null) {
            T value = deserialize(cached, type, key);
            localCache.put(key, value);
            l2Hits.increment();
            return value;
        }
        return rebuild(key, type, loader);
    }

    @SuppressWarnings("unchecked")
    private <T> T rebuild(String key, JavaType type, Supplier<T> loader) {
        Object lock = localLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // 双检：等锁期间可能已被同进程线程重建
            Object local = localCache.getIfPresent(key);
            if (local != null) {
                l1Hits.increment();
                return local == LocalCacheInvalidator.NULL ? null : (T) local;
            }
            String cached = l2Get(key);
            if (cached != null) {
                T value = deserialize(cached, type, key);
                localCache.put(key, value);
                l2Hits.increment();
                return value;
            }
            if (!tryRebuildLock(key)) {
                // 其他实例正在回源：稍候重读 L2；仍空则降级本地回源（DB 有本地单飞保护）
                sleepQuietly(properties.getRebuildWaitMillis());
                String again = l2Get(key);
                if (again != null) {
                    T value = deserialize(again, type, key);
                    localCache.put(key, value);
                    l2Hits.increment();
                    return value;
                }
                log.warn("[cache] 等待跨实例重建后 L2 仍空，降级本实例回源 key={}", key);
            } else {
                try {
                    log.info("[cache] rebuild key={} (L1 miss, L2 miss)", key);
                    return loadAndCache(key, type, loader);
                } finally {
                    releaseRebuildLock(key);
                }
            }
            return loadAndCache(key, type, loader);
        }
    }

    private <T> T loadAndCache(String key, JavaType type, Supplier<T> loader) {
        T value = loader.get();
        localCache.put(key, value);
        l2Set(key, value, type);
        rebuildsByKey.computeIfAbsent(key, k -> new LongAdder()).increment();
        return value;
    }

    /** 写路径失效：L1 清 + L2 删 + 广播 + 延迟二删（三处任一失败只告警；TTL 兜底最终一致） */
    public void evict(String key) {
        localCache.invalidate(key);
        try {
            redis.delete(CacheKeys.l2Key(key));
        } catch (RuntimeException e) {
            log.warn("[cache] L2 删除失败（L1 已失效 + TTL 兜底） key={} cause={}", key, e.getMessage());
        }
        try {
            redis.convertAndSend(properties.getInvalidateChannel(), key);
        } catch (RuntimeException e) {
            log.warn("[cache] 失效广播失败（L1 TTL 兜底） key={} cause={}", key, e.getMessage());
        }
        // 延迟双删：并发读可能在"删缓存→写库"窗口把旧值回填 L2，二次补删兜底
        if (properties.getDelayedEvictMillis() > 0) {
            try {
                delayedEvictExecutor.schedule(() -> {
                    try {
                        redis.delete(CacheKeys.l2Key(key));
                    } catch (RuntimeException e) {
                        log.debug("[cache] 延迟二删失败（TTL 兜底） key={}", key);
                    }
                }, properties.getDelayedEvictMillis(), TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                log.debug("[cache] 延迟二删调度失败（不影响主流程）: {}", e.getMessage());
            }
        }
        log.info("[cache] evict key={}", key);
    }

    @PreDestroy
    public void shutdown() {
        delayedEvictExecutor.shutdownNow();
    }

    /** 运行指标（管理端/剧本可读；rebuild:<key> 为按 key 回源计数，单飞验证用） */
    public Map<String, Long> stats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("enabled", enabled() ? 1L : 0L);
        stats.put("l1Hits", l1Hits.sum());
        stats.put("l2Hits", l2Hits.sum());
        stats.put("l1Size", localCache.size());
        long total = 0;
        for (Map.Entry<String, LongAdder> entry : rebuildsByKey.entrySet()) {
            long count = entry.getValue().sum();
            total += count;
            stats.put("rebuild:" + entry.getKey(), count);
        }
        stats.put("rebuilds", total);
        return stats;
    }

    private String l2Get(String key) {
        try {
            return redis.opsForValue().get(CacheKeys.l2Key(key));
        } catch (RuntimeException e) {
            log.warn("[cache] L2 读取失败（跳过 L2 回源） key={} cause={}", key, e.getMessage());
            return null;
        }
    }

    private void l2Set(String key, Object value, JavaType type) {
        int ttlSeconds = value == null ? properties.getNullTtlSeconds() : jitteredL2TtlSeconds();
        try {
            String json = objectMapper.writerFor(type).writeValueAsString(value);
            redis.opsForValue().set(CacheKeys.l2Key(key), json, Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[cache] L2 写入失败（不影响本次返回） key={} cause={}", key, e.getMessage());
        }
    }

    private int jitteredL2TtlSeconds() {
        int base = properties.getL2TtlSeconds();
        int jitter = Math.max(0, base * properties.getTtlJitterPercent() / 100);
        return base + (jitter == 0 ? 0 : ThreadLocalRandom.current().nextInt(jitter + 1));
    }

    private boolean tryRebuildLock(String key) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(CacheKeys.rebuildLockKey(key), "1",
                    Duration.ofSeconds(properties.getRebuildLockSeconds())));
        } catch (RuntimeException e) {
            // Redis 故障：跨实例单飞不可用，本地单飞已足够兜底
            return true;
        }
    }

    private void releaseRebuildLock(String key) {
        try {
            redis.delete(CacheKeys.rebuildLockKey(key));
        } catch (RuntimeException e) {
            log.debug("[cache] 重建锁释放失败（TTL 兜底） key={}", key);
        }
    }

    private <T> T deserialize(String json, JavaType type, String key) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("[cache] L2 JSON 反序列化失败（按未命中处理） key={} cause={}", key, e.getMessage());
            return null;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
