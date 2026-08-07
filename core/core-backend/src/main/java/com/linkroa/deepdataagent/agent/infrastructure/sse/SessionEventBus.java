package com.linkroa.deepdataagent.agent.infrastructure.sse;

import io.agentscope.core.event.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话事件路由
 * <p>管理每个活跃会话的事件流。每个会话一个 {@link Sinks.Many} 事件源，
 * 外部通过 {@link #register(String)} 注册并获取事件流，通过 {@link #unregister(String)} 注销。</p>
 *
 * <p>核心功能：</p>
 * <ul>
 *   <li>register(sessionId) — 注册会话事件流，返回 Sinks.Many 用于推入事件</li>
 *   <li>unregister(sessionId) — 注销会话事件流，释放资源</li>
 *   <li>活跃会话上限检查 — maxActiveSessions 达上限时拒绝新会话</li>
 *   <li>事件缓冲区管理 — sinkBufferSize 超限时丢弃最旧事件</li>
 *   <li>getActiveSessionCount() — 获取当前活跃会话数</li>
 * </ul>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * // 注册会话
 * Sinks.Many<AgentEvent> sink = sessionEventBus.register(sessionId);
 * // 推入事件
 * sink.tryEmitNext(event);
 * // 注销会话
 * sessionEventBus.unregister(sessionId);
 * }</pre>
 */
@Component
public class SessionEventBus {

    private static final Logger log = LoggerFactory.getLogger(SessionEventBus.class);

    /** 会话事件源映射：sessionId -> Sinks.Many */
    private final Map<String, Sinks.Many<AgentEvent>> sinks = new ConcurrentHashMap<>();

    /** 活跃会话数计数器 */
    private final AtomicInteger activeSessionCount = new AtomicInteger(0);

    /** 事件总线配置 */
    private final SessionEventBusProperties properties;

    /**
     * 构造方法
     *
     * @param properties 会话事件总线配置
     */
    public SessionEventBus(SessionEventBusProperties properties) {
        this.properties = properties;
        log.info("SessionEventBus initialized: maxActiveSessions={}, sinkBufferSize={}",
                properties.maxActiveSessions(), properties.sinkBufferSize());
    }

    /**
     * 注册会话事件流
     * <p>创建一个新的 {@link Sinks.Many}，用于接收该会话的 Agent 事件。
     * 如果已达到最大活跃会话数，返回 null。</p>
     *
     * @param sessionId 会话 ID
     * @return Sinks.Many 实例，用于推入事件；如果达到上限返回 null
     */
    public Sinks.Many<AgentEvent> register(String sessionId) {
        // 检查是否已注册
        Sinks.Many<AgentEvent> existing = sinks.get(sessionId);
        if (existing != null) {
            return existing;
        }

        // 检查是否达到上限
        if (activeSessionCount.get() >= properties.maxActiveSessions()) {
            log.warn("Session event bus exhausted: maxActiveSessions={}, current={}",
                    properties.maxActiveSessions(), activeSessionCount.get());
            return null;
        }

        // 创建带缓冲区的事件源，缓冲区满时丢弃最旧事件
        Sinks.Many<AgentEvent> sink = Sinks.many()
                .multicast()
                .onBackpressureBuffer(properties.sinkBufferSize(), false);

        sinks.put(sessionId, sink);
        activeSessionCount.incrementAndGet();

        log.info("Session registered: sessionId={}, activeCount={}",
                sessionId, activeSessionCount.get());
        return sink;
    }

    /**
     * 注销会话事件流
     * <p>移除指定会话的事件源，并尝试完成（emitComplete）以释放下游资源。</p>
     *
     * @param sessionId 会话 ID
     */
    public void unregister(String sessionId) {
        Sinks.Many<AgentEvent> removed = sinks.remove(sessionId);
        if (removed != null) {
            activeSessionCount.decrementAndGet();
            // 尝试完成事件流，忽略失败（可能已终止）
            removed.tryEmitComplete();
            log.info("Session unregistered: sessionId={}, activeCount={}",
                    sessionId, activeSessionCount.get());
        }
    }

    /**
     * 获取会话的事件流
     * <p>返回该会话的 {@link Flux} 视图，用于外部订阅。</p>
     *
     * @param sessionId 会话 ID
     * @return 会话事件流 Flux，如果未注册返回 null
     */
    public Flux<AgentEvent> getEventStream(String sessionId) {
        Sinks.Many<AgentEvent> sink = sinks.get(sessionId);
        if (sink == null) {
            return null;
        }
        return sink.asFlux();
    }

    /**
     * 获取当前活跃会话数
     *
     * @return 活跃会话数
     */
    public int getActiveSessionCount() {
        return activeSessionCount.get();
    }

    /**
     * 检查会话是否已注册
     *
     * @param sessionId 会话 ID
     * @return true 如果已注册
     */
    public boolean isRegistered(String sessionId) {
        return sinks.containsKey(sessionId);
    }
}