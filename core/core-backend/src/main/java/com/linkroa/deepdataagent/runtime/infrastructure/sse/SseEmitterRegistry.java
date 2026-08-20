package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.application.assembler.SseEventEnvelopeAssembler;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话级 SSE 连接注册表（{@link SseConnectionHandle} 的会话级工厂 / 容器 + 全局心跳）。
 * <p>连接生命周期随会话绑定到 {@code AgentSessionContext.connection()}；本类只负责：</p>
 * <ul>
 *   <li>按会话获取 / 原子重建 {@link SseConnectionHandle}（句柄关闭后自动新建）；</li>
 *   <li>创建受超时 / 断连保护的 {@link SseEmitter} 并挂入句柄连接组；</li>
 *   <li>全局心跳保活：周期对全部存活句柄发送注释行、探测并回收死连接。</li>
 * </ul>
 * <p>不再承担「广播 / 完成」职责（领域广播已改经句柄 {@code push(ChatEvent)}，
 * 关闭全部订阅者由句柄 {@code close()} 承载并由 {@code bindConnection} 触发），
 * 也不再跨层反调领域聚合（断连由连接层 {@code onDisconnect} 回调收口）。</p>
 */
@Slf4j
@Component
public class SseEmitterRegistry {

    /** 心跳周期下限（防止 sse-timeout 配置过小时周期被推导为 0） */
    private static final Duration HEARTBEAT_MIN_INTERVAL = Duration.ofSeconds(10);

    private final Map<String, SseConnectionHandle> handles = new ConcurrentHashMap<>();
    @Resource
    private AgentRuntimeProperties properties;
    @Resource
    private SseEventEnvelopeAssembler envelopeAssembler;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ScheduledExecutorService heartbeatExecutor;

    @PostConstruct
    void startHeartbeat() {
        if (started.compareAndSet(false, true)) {
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            Duration interval = heartbeatInterval();
            heartbeatExecutor.scheduleWithFixedDelay(
                    this::heartbeat, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 心跳周期 = 配置 SSE 空闲超时的 1/3（保证连接被容器回收前已多次保活），下限 10s。
     */
    private Duration heartbeatInterval() {
        long candidate = properties.getSseTimeout().toMillis() / 3;
        return candidate > HEARTBEAT_MIN_INTERVAL.toMillis()
                ? Duration.ofMillis(candidate)
                : HEARTBEAT_MIN_INTERVAL;
    }

    @PreDestroy
    void stopHeartbeat() {
        if (started.compareAndSet(true, false) && heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
    }

    /**
     * 获取或原子创建会话连接句柄；既有句柄已关闭时原子重建。
     *
     * @param sessionId 会话 ID
     * @return 该会话当前活跃的连接句柄（同一会话同时复用一个句柄，共享连接组）
     */
    public SseConnectionHandle getOrCreate(String sessionId) {
        return handles.compute(sessionId, (k, existing) ->
                existing == null || existing.isClosed()
                        ? new SseConnectionHandle(envelopeAssembler)
                        : existing);
    }

    /**
     * 注册一个订阅者（创建受超时 / 断连保护的 emitter 并挂入句柄连接组）。
     *
     * @param handle  会话连接句柄
     * @param timeout SSE 空闲超时
     * @return 已完成回调接线的 emitter
     */
    public SseEmitter register(SseConnectionHandle handle, Duration timeout) {
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        handle.addConnection(emitter);
        Runnable cleanup = () -> handle.removeConnection(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    /**
     * 移除会话连接句柄（会话级清理，非必要场景可由外部显式调用）。
     *
     * @param sessionId 会话 ID
     */
    public void remove(String sessionId) {
        handles.remove(sessionId);
    }

    /**
     * 心跳：逐句柄发送注释保活、探测死连接并回收，顺带清理已关闭的句柄。
     * <p>Spring WebMvc 7 不再支持 {@code extendTimeout}（超时在初始化时固定），
     * 因此连接级超时由 {@link AgentRuntimeProperties#getSseTimeout()} 控制，
     * 此处仅负责客户端侧保活与死连接即时回收。</p>
     */
    private void heartbeat() {
        handles.values().removeIf(handle -> {
            if (handle.isClosed()) {
                return true;
            }
            handle.heartbeat();
            return false;
        });
    }
}