package com.swapops.server.config;

import com.swapops.server.common.cache.LocalCacheInvalidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 缓存装配（S3.8 WP1）：订阅失效广播频道 → 本进程 L1 失效。
 */
@Configuration
public class CacheConfig {

    @Bean
    @ConditionalOnProperty(prefix = "swap.cache", name = "listener-enabled",
            havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer cacheInvalidateListenerContainer(
            RedisConnectionFactory connectionFactory, LocalCacheInvalidator invalidator,
            CacheProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(invalidator, new ChannelTopic(properties.getInvalidateChannel()));
        return container;
    }
}
