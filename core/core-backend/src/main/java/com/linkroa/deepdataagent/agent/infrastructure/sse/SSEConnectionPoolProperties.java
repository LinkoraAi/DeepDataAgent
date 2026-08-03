package com.linkroa.deepdataagent.agent.infrastructure.sse;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE 连接池配置
 * <p>映射 application.yml 中 sse.connection-pool.* 配置项。</p>
 *
 * @param maxActive 最大活跃连接数，默认 500
 * @param keepAliveMs 连接保活时间（毫秒），默认 30000
 */
@ConfigurationProperties(prefix = "sse.connection-pool")
public record SSEConnectionPoolProperties(
    int maxActive,
    long keepAliveMs
) {
    public SSEConnectionPoolProperties {
        if (maxActive <= 0) maxActive = 500;
        if (keepAliveMs <= 0) keepAliveMs = 30000L;
    }
}