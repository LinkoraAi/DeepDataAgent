package com.linkroa.deepdataagent.agent.infrastructure.sse.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 执行池配置
 * <p>映射 application.yml 中 agent.execution.* 配置项。</p>
 *
 * @param maxActiveSessions 最大活跃会话数，默认 500
 * @param virtualThreads 是否启用虚拟线程，默认 true
 */
@ConfigurationProperties(prefix = "agent.execution")
public record AgentExecutionPoolProperties(
    int maxActiveSessions,
    boolean virtualThreads
) {
    public AgentExecutionPoolProperties {
        if (maxActiveSessions <= 0) maxActiveSessions = 500;
    }
}