package com.swapops.server;

import com.swapops.server.common.cache.TwoLevelCacheService;
import com.swapops.server.common.ratelimit.RateLimitAspect;
import com.swapops.server.common.resilience.DeviceDownlinkGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配冒烟（S3.8 WP4）：不依赖 MySQL/Redis 的上下文加载测试——把"单测全绿但起不来"提前到 CI。
 * 通过测试属性绕开外部依赖：workerId 显式、缓存监听/MQ 消费关闭、Hikari 不预热、密钥用假值。
 */
@DisplayName("Spring 上下文装配冒烟")
@SpringBootTest(properties = {
        "swap.pay.secret=0123456789abcdef0123456789abcdef",
        "swap.admin.token=fedcba9876543210fedcba9876543210",
        "swap.id.worker-id=0",
        "swap.cache.enabled=false",
        "swap.cache.listener-enabled=false",
        "swap.device.mq.enabled=false",
        "swap.dev=false",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.task.scheduling.pool.size=1",
        "spring.main.banner-mode=off"
})
class ContextLoadsTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("上下文加载且关键 Bean 可注入（S3.8 各组件装配正确 + S7 WP-A 安全链）")
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(DeviceDownlinkGuard.class)).isNotNull();
        assertThat(context.getBean(TwoLevelCacheService.class)).isNotNull();
        assertThat(context.getBean(RateLimitAspect.class)).isNotNull();
        assertThat(context.getBean("applicationTaskExecutor")).isNotNull();
        assertThat(context.getBean(SwapServerApplication.class)).isNotNull();
        assertThat(context.getBean(com.swapops.server.admin.security.AdminAuthFilter.class)).isNotNull();
        assertThat(context.getBean("adminSecurityFilterChain")).isNotNull();
    }
}
