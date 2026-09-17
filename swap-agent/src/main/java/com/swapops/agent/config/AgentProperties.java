package com.swapops.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 配置。admin token 经环境变量 SWAP_AGENT_ADMIN_TOKEN 注入（仓库零明文）；
 * enabled=true 且 token 缺失时由 {@link com.swapops.agent.client.PlatformClient} 构造期 fail-fast。
 */
@Data
@ConfigurationProperties(prefix = "swap.agent")
public class AgentProperties {

    /** 是否启用自动轮询扫描（默认关：独立进程显式启用，防止意外进程骚扰平台） */
    private boolean enabled = false;

    /** 平台基址（含 context-path） */
    private String platformBaseUrl = "http://127.0.0.1:8400/api";

    /** 管理端静态 token（break-glass；env 注入） */
    private String adminToken = "";

    /** 轮询间隔（毫秒） */
    private long pollIntervalMs = 30000;

    /** 单轮拉取未处理告警上限 */
    private int alarmLimit = 200;

    /** 事故摘要窗口（分钟；"首现 N 小时间"的聚合窗） */
    private int summaryWindowMinutes = 60;

    /** 连接/读取超时（毫秒） */
    private int timeoutMs = 5000;
}
