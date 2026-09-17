package com.swapops.agent;

import com.swapops.agent.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 运维 Agent 最小版（S6）入口。
 *
 * <p>设计原则（与平台侧接缝对称）：
 * <ul>
 *   <li>观测口只读消费平台管理接口（告警/工单/看板）；</li>
 *   <li>任何写动作只走"建议单"（propose，无副作用，幂等键去重），执行必须人工 confirm；</li>
 *   <li>平台侧对 Agent 零依赖——Agent 进程退出/异常不影响平台任何主流程。</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
@EnableScheduling
public class SwapAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwapAgentApplication.class, args);
    }
}
