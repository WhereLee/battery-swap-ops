package com.swapops.server.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.swapops.server.config.CacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * L1（Caffeine）持有者 + 失效广播接收端（S3.8 WP1）：
 * 本进程内单飞/命中/淘汰；跨实例失效由 Redis Pub/Sub 通知（消息丢失由 L1 TTL 兜底）。
 */
@Slf4j
@Component
public class LocalCacheInvalidator implements MessageListener {

    /** 空值哨兵（Caffeine 不允许 null，空值缓存需显式标记） */
    static final Object NULL = new Object();

    private final Cache<String, Object> l1;

    public LocalCacheInvalidator(CacheProperties properties) {
        this.l1 = Caffeine.newBuilder()
                .expireAfterWrite(java.time.Duration.ofSeconds(properties.getL1TtlSeconds()))
                .maximumSize(properties.getL1MaxSize())
                .build();
    }

    Object getIfPresent(String key) {
        return l1.getIfPresent(key);
    }

    void put(String key, Object value) {
        l1.put(key, value == null ? NULL : value);
    }

    void invalidate(String key) {
        l1.invalidate(key);
    }

    public long size() {
        l1.cleanUp();
        return l1.estimatedSize();
    }

    /** 收到失效广播：清本进程 L1（幂等） */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String key = new String(message.getBody(), StandardCharsets.UTF_8);
        invalidate(key);
        log.debug("[cache] L1 失效广播命中 key={}", key);
    }
}
