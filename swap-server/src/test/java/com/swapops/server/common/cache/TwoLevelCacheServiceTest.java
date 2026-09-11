package com.swapops.server.common.cache;

import com.swapops.server.config.CacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 两级缓存单测（S3.8 WP1）：L1/L2 命中、空值缓存、单飞、失效广播、TTL 抖动、Redis 故障降级。
 */
@DisplayName("两级缓存（L1+L2）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TwoLevelCacheServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private CacheProperties properties;
    private LocalCacheInvalidator local;
    private TwoLevelCacheService service;
    private final Map<String, String> store = new ConcurrentHashMap<>();
    private final List<Duration> setTtls = new CopyOnWriteArrayList<>();
    private boolean redisDown = false;

    static class Item {
        public String name;

        public Item() {
        }

        Item(String name) {
            this.name = name;
        }
    }

    @BeforeEach
    void setUp() {
        properties = new CacheProperties();
        local = new LocalCacheInvalidator(properties);
        service = new TwoLevelCacheService(redis, properties, local);
        store.clear();
        setTtls.clear();
        redisDown = false;

        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> {
            if (redisDown) {
                throw new RuntimeException("redis down");
            }
            return store.get(inv.getArgument(0));
        });
        doAnswer(inv -> {
            if (redisDown) {
                throw new RuntimeException("redis down");
            }
            store.put(inv.getArgument(0), inv.getArgument(1));
            setTtls.add(inv.getArgument(2));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(inv -> store.remove(inv.getArgument(0)) != null);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.convertAndSend(anyString(), anyString())).thenReturn(1L);
    }

    @Test
    @DisplayName("L1 命中：同 key 二次读取不再回源")
    void L1命中() {
        AtomicInteger loads = new AtomicInteger();
        service.getList("list:a", Item.class, () -> {
            loads.incrementAndGet();
            return List.of(new Item("x"));
        });
        service.getList("list:a", Item.class, () -> {
            loads.incrementAndGet();
            return List.of(new Item("y"));
        });

        assertThat(loads.get()).isEqualTo(1);
        assertThat(service.stats().get("l1Hits")).isEqualTo(1);
    }

    @Test
    @DisplayName("L2 命中：新实例（空 L1）从 Redis 取数，不回源")
    void L2命中() {
        service.getList("list:b", Item.class, () -> List.of(new Item("shared")));

        TwoLevelCacheService second = new TwoLevelCacheService(redis, properties,
                new LocalCacheInvalidator(properties));
        AtomicInteger loads = new AtomicInteger();
        List<Item> result = second.getList("list:b", Item.class, () -> {
            loads.incrementAndGet();
            return List.of(new Item("db"));
        });

        assertThat(loads.get()).isZero();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name).isEqualTo("shared");
    }

    @Test
    @DisplayName("空值缓存：loader 返回 null 也入缓存（防穿透），二次不回源")
    void 空值缓存() {
        AtomicInteger loads = new AtomicInteger();
        Item first = service.getEntity("entity:null", Item.class, () -> {
            loads.incrementAndGet();
            return null;
        });
        Item second = service.getEntity("entity:null", Item.class, () -> {
            loads.incrementAndGet();
            return new Item("db");
        });

        assertThat(first).isNull();
        assertThat(second).isNull();
        assertThat(loads.get()).isEqualTo(1);
        assertThat(store).containsValue("null");
    }

    @Test
    @DisplayName("evict：L1/L2 清 + 广播；下次读取回源")
    void 失效与广播() {
        AtomicInteger loads = new AtomicInteger();
        service.getEntity("entity:e", Item.class, () -> {
            loads.incrementAndGet();
            return new Item("v1");
        });
        service.evict("entity:e");
        Item after = service.getEntity("entity:e", Item.class, () -> {
            loads.incrementAndGet();
            return new Item("v2");
        });

        assertThat(loads.get()).isEqualTo(2);
        assertThat(after.name).isEqualTo("v2");
        verify(redis).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("远端失效广播：对端 L2（含其 L1）清后下次回源")
    void 广播联动对端() {
        LocalCacheInvalidator peerLocal = new LocalCacheInvalidator(properties);
        TwoLevelCacheService peer = new TwoLevelCacheService(redis, properties, peerLocal);
        AtomicInteger peerLoads = new AtomicInteger();
        service.getEntity("entity:p", Item.class, () -> new Item("v1"));
        peer.getEntity("entity:p", Item.class, () -> {
            peerLoads.incrementAndGet();
            return new Item("peer-v1");
        });

        service.evict("entity:p");
        // 模拟广播到达对端（生产由 RedisMessageListenerContainer 触发）
        org.springframework.data.redis.connection.Message message =
                org.mockito.Mockito.mock(org.springframework.data.redis.connection.Message.class);
        when(message.getBody()).thenReturn("entity:p".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        peerLocal.onMessage(message, null);

        Item after = peer.getEntity("entity:p", Item.class, () -> {
            peerLoads.incrementAndGet();
            return new Item("peer-v2");
        });

        assertThat(peerLoads.get()).isEqualTo(1); // 首次从 L2 命中，不加载；失效后必须回源
        assertThat(after.name).isEqualTo("peer-v2");
    }

    @Test
    @DisplayName("延迟双删：evict 后窗口内被读回填的旧值会被二删清掉")
    void 延迟双删() throws Exception {
        properties.setDelayedEvictMillis(100);
        service.getEntity("entity:d", Item.class, () -> new Item("v1"));
        service.evict("entity:d");
        // 模拟竞态：删缓存后、DB 提交前，另一个读把旧值回填 L2
        store.put(CacheKeys.l2Key("entity:d"), "{\"data\":{\"name\":\"stale\"}}");

        Thread.sleep(300);

        assertThat(store).doesNotContainKey(CacheKeys.l2Key("entity:d"));
    }

    @Test
    @DisplayName("TTL 抖动：L2 写入时长在 [base, base+jitter] 内")
    void TTL抖动() {
        properties.setL2TtlSeconds(100);
        properties.setTtlJitterPercent(20);
        service.getEntity("entity:ttl", Item.class, () -> new Item("v"));

        assertThat(setTtls).hasSize(1);
        long seconds = setTtls.get(0).getSeconds();
        assertThat(seconds).isBetween(100L, 120L);
    }

    @Test
    @DisplayName("Redis 故障降级：读路径跳过 L2 直读 DB（L1 仍生效），不抛错")
    void Redis故障降级() {
        redisDown = true;
        AtomicInteger loads = new AtomicInteger();
        Item first = service.getEntity("entity:down", Item.class, () -> {
            loads.incrementAndGet();
            return new Item("db");
        });
        Item second = service.getEntity("entity:down", Item.class, () -> {
            loads.incrementAndGet();
            return new Item("db");
        });

        assertThat(first.name).isEqualTo("db");
        assertThat(second.name).isEqualTo("db");
        assertThat(loads.get()).isEqualTo(1); // L1 兜底
    }

    @Test
    @DisplayName("本地单飞：并发同 key 只回源一次")
    void 本地单飞() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.getEntity("entity:fly", Item.class, () -> {
                        loads.incrementAndGet();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return new Item("v");
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(loads.get()).isEqualTo(1);
    }
}
