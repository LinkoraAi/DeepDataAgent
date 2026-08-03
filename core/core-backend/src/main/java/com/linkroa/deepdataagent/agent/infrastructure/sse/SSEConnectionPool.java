package com.linkroa.deepdataagent.agent.infrastructure.sse;

import io.agentscope.core.event.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 连接池
 * <p>管理每个用户的 SSE 连接生命周期。每个用户（clientId）维护一个 SseEmitter，
 * 所有会话的事件通过同一个连接推送，前端按 sessionId 路由。</p>
 *
 * <p>核心功能：</p>
 * <ul>
 *   <li>acquire(clientId) — 创建或复用连接，注册 onCompletion/onTimeout/onError 回调</li>
 *   <li>release(clientId) — 移除连接，释放资源</li>
 *   <li>sendEvent(clientId, sessionId, event) — 通过连接发送事件，携带 sessionId</li>
 *   <li>isConnected(clientId) — 检查连接是否活跃</li>
 *   <li>getActiveConnectionCount() — 获取活跃连接数</li>
 *   <li>30 秒空闲回收 — 使用 ScheduledExecutorService 定期检查空闲连接</li>
 *   <li>maxActive 上限检查 — 达到上限时拒绝新连接</li>
 * </ul>
 */
@Component
public class SSEConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(SSEConnectionPool.class);

    /** 客户端连接映射：clientId -> SseEmitter 包装体 */
    private final Map<String, SseEmitter> connections = new ConcurrentHashMap<>();

    /** 客户端最后活跃时间映射：clientId -> lastActiveTime（毫秒时间戳） */
    private final Map<String, Long> lastActiveTimes = new ConcurrentHashMap<>();

    /** 会话与 clientId 的映射：sessionId -> latest clientId */
    private final Map<String, String> sessionClientIdMap = new ConcurrentHashMap<>();

    /** 活跃连接数计数器 */
    private final AtomicInteger activeCount = new AtomicInteger(0);

    /** 连接池配置 */
    private final SSEConnectionPoolProperties properties;

    /** 空闲连接回收定时器 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-idle-reaper");
        t.setDaemon(true);
        return t;
    });

    /** 心跳定时器：每 15 秒发送一次心跳，保持连接活跃 */
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /**
     * 构造方法
     *
     * @param properties SSE 连接池配置
     */
    public SSEConnectionPool(SSEConnectionPoolProperties properties) {
        this.properties = properties;
        // 每 10 秒检查一次空闲连接
        this.scheduler.scheduleAtFixedRate(this::reapIdleConnections, 10, 10, TimeUnit.SECONDS);
        // 每 15 秒发送一次心跳，保持连接活跃（小于 keepAliveMs 30秒）
        this.heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
        log.info("SSEConnectionPool initialized: maxActive={}, keepAliveMs={}",
                properties.maxActive(), properties.keepAliveMs());
    }

    /**
     * 获取或创建 SSE 连接
     * <p>如果指定 clientId 已有活跃连接，直接返回；否则创建新的 SseEmitter。</p>
     *
     * @param clientId 客户端 ID
     * @return SseEmitter 实例；如果达到上限则返回 null
     */
    public SseEmitter acquire(String clientId) {
        // 检查是否已存在连接
        SseEmitter existing = connections.get(clientId);
        if (existing != null) {
            updateLastActiveTime(clientId);
            return existing;
        }

        // 检查是否达到上限
        if (activeCount.get() >= properties.maxActive()) {
            log.warn("SSE connection pool exhausted: maxActive={}, current={}",
                    properties.maxActive(), activeCount.get());
            return null;
        }

        // 创建新连接
        SseEmitter emitter = new SseEmitter(properties.keepAliveMs());
        connections.put(clientId, emitter);
        activeCount.incrementAndGet();
        updateLastActiveTime(clientId);

        // 注册回调
        emitter.onCompletion(() -> {
            log.info("SSE connection completed for clientId={}", clientId);
            removeConnection(clientId);
        });

        emitter.onTimeout(() -> {
            log.warn("SSE connection timeout for clientId={}", clientId);
            removeConnection(clientId);
        });

        emitter.onError(e -> {
            log.error("SSE connection error for clientId={}", clientId, e);
            removeConnection(clientId);
        });

        log.info("SSE connection acquired for clientId={}, activeCount={}", clientId, activeCount.get());
        return emitter;
    }

    /**
     * 释放客户端 SSE 连接
     *
     * @param clientId 客户端 ID
     */
    public void release(String clientId) {
        SseEmitter emitter = connections.remove(clientId);
        if (emitter != null) {
            lastActiveTimes.remove(clientId);
            activeCount.decrementAndGet();
            try {
                emitter.complete();
            } catch (Exception e) {
                log.warn("Failed to complete SSE emitter for clientId={}", clientId, e);
            }
            log.info("SSE connection released for clientId={}, activeCount={}", clientId, activeCount.get());
        }
    }

    /**
     * 通过连接发送事件
     * <p>事件格式：event: ANALYSIS_EVENT, data: {"sessionId": "xxx", "event": {...}}</p>
     *
     * @param clientId 客户端 ID
     * @param sessionId 会话 ID
     * @param event Agent 事件
     * @return true 如果发送成功，false 如果连接不存在或发送失败
     */
    public boolean sendEvent(String clientId, String sessionId, AgentEvent event) {
        SseEmitter emitter = connections.get(clientId);
        if (emitter == null) {
            log.warn("No SSE connection found for clientId={}", clientId);
            return false;
        }

        try {
            Map<String, Object> wrappedEvent = Map.of(
                    "sessionId", sessionId,
                    "event", event
            );
            emitter.send(SseEmitter.event()
                    .name("ANALYSIS_EVENT")
                    .data(wrappedEvent, MediaType.APPLICATION_JSON));
            updateLastActiveTime(clientId);
            return true;
        } catch (IOException e) {
            log.error("Failed to send SSE event to clientId={}", clientId, e);
            removeConnection(clientId);
            return false;
        }
    }

    /**
     * 检查客户端是否已连接
     *
     * @param clientId 客户端 ID
     * @return true 如果已连接且活跃
     */
    public boolean isConnected(String clientId) {
        return connections.containsKey(clientId);
    }

    /**
     * 获取当前活跃连接数
     *
     * @return 活跃连接数
     */
    public int getActiveConnectionCount() {
        return activeCount.get();
    }

    /**
     * 更新会话的 clientId 映射
     * <p>当 SSE 连接重连导致 clientId 变更时，更新 sessionId 对应的最新 clientId。
     * 后续分析事件将发送到新的 clientId 对应的连接。</p>
     *
     * @param sessionId 会话 ID
     * @param clientId 最新的客户端 ID
     */
    public void updateSessionClientId(String sessionId, String clientId) {
        sessionClientIdMap.put(sessionId, clientId);
        log.debug("Session clientId updated: sessionId={}, clientId={}", sessionId, clientId);
    }

    /**
     * 获取会话对应的最新 clientId
     *
     * @param sessionId 会话 ID
     * @return 最新的 clientId，如果不存在则返回 null
     */
    public String getClientIdForSession(String sessionId) {
        return sessionClientIdMap.get(sessionId);
    }

    /**
     * 移除会话的 clientId 映射
     * <p>分析完成或出错时调用，清理映射关系。</p>
     *
     * @param sessionId 会话 ID
     */
    public void removeSessionClientId(String sessionId) {
        sessionClientIdMap.remove(sessionId);
        log.debug("Session clientId removed: sessionId={}", sessionId);
    }

    /**
     * 更新客户端最后活跃时间
     *
     * @param clientId 客户端 ID
     */
    private void updateLastActiveTime(String clientId) {
        lastActiveTimes.put(clientId, System.currentTimeMillis());
    }

    /**
     * 移除连接并释放资源
     *
     * @param clientId 客户端 ID
     */
    private void removeConnection(String clientId) {
        SseEmitter removed = connections.remove(clientId);
        if (removed != null) {
            lastActiveTimes.remove(clientId);
            activeCount.decrementAndGet();
            log.debug("SSE connection removed for clientId={}, activeCount={}", clientId, activeCount.get());
        }
    }

    /**
     * 回收空闲连接
     * <p>定期检查所有连接，超过 keepAliveMs 无事件的连接自动断开。</p>
     */
    private void reapIdleConnections() {
        long now = System.currentTimeMillis();
        long keepAliveMs = properties.keepAliveMs();

        for (Map.Entry<String, Long> entry : lastActiveTimes.entrySet()) {
            String clientId = entry.getKey();
            long lastActive = entry.getValue();
            if (now - lastActive > keepAliveMs) {
                log.info("Reaping idle SSE connection for clientId={}, idleMs={}",
                        clientId, now - lastActive);
                release(clientId);
            }
        }
    }

    /**
     * 发送心跳
     * <p>定期向所有活跃连接发送心跳事件，保持连接活跃，防止因长时间无数据传输导致连接超时断开</p>
     * <p>心跳发送成功后更新 lastActiveTime，避免连接被误判为空闲而回收</p>
     */
    private void sendHeartbeats() {
        for (String clientId : connections.keySet()) {
            SseEmitter emitter = connections.get(clientId);
            if (emitter != null) {
                try {
                    // 发送 SSE 注释作为心跳，格式：: heartbeat\n\n
                    // 这种格式会被客户端忽略，但能保持连接活跃
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    // 心跳发送成功后更新活跃时间，避免连接被误判为空闲
                    updateLastActiveTime(clientId);
                    log.debug("Heartbeat sent to clientId={}", clientId);
                } catch (Exception e) {
                    log.warn("Failed to send heartbeat to clientId={}, removing connection", clientId, e);
                    removeConnection(clientId);
                }
            }
        }
    }
}