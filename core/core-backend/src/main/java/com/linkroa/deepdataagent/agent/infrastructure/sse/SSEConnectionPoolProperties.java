package com.linkroa.deepdataagent.agent.infrastructure.sse;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE 连接池配置
 * <p>映射 application.yml 中 sse.connection-pool.* 配置项。</p>
 *
 * @param maxActive 最大活跃连接数，默认 500
 * @param keepAliveMs 空闲回收阈值（毫秒），默认 30000：连接超过该时长既无事件发送也无心跳成功时，
 *                    由应用层空闲回收（reapIdleConnections）判定为失效并回收。该值不参与 SseEmitter 的
 *                    容器级超时设置（连接以 SseEmitter(0L) 创建，容器不设超时）。
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