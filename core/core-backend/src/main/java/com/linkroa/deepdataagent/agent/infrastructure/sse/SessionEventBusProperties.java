package com.linkroa.deepdataagent.agent.infrastructure.sse;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话事件总线配置
 * <p>映射 application.yml 中 sse.event-bus.* 配置项。</p>
 *
 * @param maxActiveSessions 最大活跃会话数，默认 500
 * @param sinkBufferSize 事件缓冲区大小，默认 1024
 */
@ConfigurationProperties(prefix = "sse.event-bus")
public record SessionEventBusProperties(
    int maxActiveSessions,
    int sinkBufferSize
) {
    public SessionEventBusProperties {
        if (maxActiveSessions <= 0) maxActiveSessions = 500;
        if (sinkBufferSize <= 0) sinkBufferSize = 1024;
    }
}