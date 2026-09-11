package com.swapops.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 两级缓存参数（swap.cache.*）：L1=Caffeine（本进程），L2=Redis（跨实例）。
 * 仅服务"展示/字典"类读；资金、库存、订单状态禁止入缓存（红线见 S3.8 方案 §四）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.cache")
public class CacheProperties {

    /** 总开关（联调可关：读直落 DB） */
    private boolean enabled = true;

    /** L1 存活秒（短，跨实例失效靠 Pub/Sub，丢消息靠该 TTL 兜底） */
    private int l1TtlSeconds = 30;

    /** L1 最大键数（防本进程内存被击穿） */
    private int l1MaxSize = 10000;

    /** L2 存活秒（基础值，实际写入带抖动） */
    private int l2TtlSeconds = 300;

    /** 空值缓存秒（防穿透；短 TTL 容错新增数据） */
    private int nullTtlSeconds = 30;

    /** L2 TTL 抖动百分比（防雪崩：批量键不同时过期） */
    private int ttlJitterPercent = 10;

    /** 跨实例重建锁 TTL 秒（单飞：同一 key 只有一个实例回源 DB） */
    private int rebuildLockSeconds = 5;

    /** 未抢到重建锁时的等待毫秒（等待后重读 L2） */
    private long rebuildWaitMillis = 200;

    /** 失效广播频道 */
    private String invalidateChannel = "swap:cache:invalidate";

    /** 延迟双删毫秒（防"旧值读回填"竞态；二删只删 L2，不再广播 L1） */
    private long delayedEvictMillis = 500;

    /** 失效广播监听容器开关（单测/无 Redis 环境关闭；生产必须开启） */
    private boolean listenerEnabled = true;
}
