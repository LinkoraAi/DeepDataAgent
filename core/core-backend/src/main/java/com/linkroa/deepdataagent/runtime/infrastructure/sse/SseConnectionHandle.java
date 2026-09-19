package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.application.convert.SseEventEnvelopeConvert;
import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 连接句柄 —— 领域端口 {@link ConnectionHandle} 的进程内 SSE 适配实现。
 * <p>表达「一个会话对应一组连接」的多订阅者 fan-out 语义：内部持有该会话的
 * {@link SseEmitter} → 协商参数连接组，{@link #push} 将 buffered 领域事件经
 * {@link SseEventEnvelopeConvert} 转换为 {@code SseEventEnvelope} 后广播全部连接；
 * {@link #pushFrame} 将流式增量帧<b>仅</b>下发给显式协商了目标事件类型的连接
 * （连接级协商，互不影响）；协议细节（信封 / 帧 → SSE）完全不泄漏到领域层。</p>
 * <ul>
 *   <li>{@link #removeConnection} 仅从连接组摘除单个连接，<b>MUST NOT</b> 触发任何取消 /
 *       中断副作用（design: 断连不取消——SSE 只是观察通道，客户端断线重连回放即可）；</li>
 *   <li>{@link #close} 完成全部订阅者（会话终止 / 句柄替换时经
 *       {@code AgentSessionContext.bindConnection} 触发旧句柄关闭）。</li>
 * </ul>
 * <p>同一会话的 {@link #push} 由事件全序保证串行；内部用 {@link ConcurrentHashMap}
 * 承载连接组以兼容心跳 / 断连 / 增量推送的并发访问。</p>
 */
@Slf4j
public class SseConnectionHandle implements ConnectionHandle {

    /** 增量刷写间隔缺省值（毫秒），对齐 {@code delta_flush_interval_ms} 契约缺省。 */
    public static final long DEFAULT_DELTA_FLUSH_INTERVAL_MS = 50L;

    /**
     * 连接级增量协商快照（注册连接时装配，仅作用于当前连接）。
     *
     * @param deltaTargets        该连接协商的增量目标类型集（空 = 仅 buffered 完整事件）
     * @param deltaFlushIntervalMs 该连接协商的增量刷写间隔（毫秒）
     */
    private record Negotiation(Set<ChatEventType> deltaTargets, long deltaFlushIntervalMs) {
    }

    /** 会话连接组（fan-out 目标 → 各连接协商参数）。 */
    private final Map<SseEmitter, Negotiation> connections = new ConcurrentHashMap<>();
    /** 句柄是否已关闭（关闭后拒绝新增连接、push 为空操作）。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @Override
    public void push(ChatEvent event) {
        pushExcluding(event, Set.of());
    }

    @Override
    public void pushExcluding(ChatEvent event, Set<?> excludedConnections) {
        if (closed.get() || connections.isEmpty()) {
            return;
        }
        SseEventEnvelope envelope = SseEventEnvelopeConvert.INSTANCE.toEnvelope(event);
        for (SseEmitter emitter : connections.keySet()) {
            if (excludedConnections.contains(emitter)) {
                continue;
            }
            send(emitter, envelope);
        }
    }

    /**
     * 流式增量帧广播：仅下发给协商了 {@code frame.targetType()} 的连接
     * （未协商的连接只收 buffered 完整事件，多连接互不影响）。
     */
    @Override
    public void pushFrame(StreamFrame frame) {
        if (closed.get() || frame == null) {
            return;
        }
        for (Map.Entry<SseEmitter, Negotiation> entry : connections.entrySet()) {
            if (!entry.getValue().deltaTargets().contains(frame.targetType())) {
                continue;
            }
            sendFrame(entry.getKey(), frame);
        }
    }

    /**
     * 增量刷写间隔：连接组内所有「协商了增量帧」连接的最短间隔
     * （以最快速度刷写，慢连接由各自过滤兜底）；无增量订阅者时返回缺省 50ms。
     */
    @Override
    public long deltaFlushIntervalMs() {
        return connections.values().stream()
                .filter(negotiation -> !negotiation.deltaTargets().isEmpty())
                .mapToLong(Negotiation::deltaFlushIntervalMs)
                .min()
                .orElse(DEFAULT_DELTA_FLUSH_INTERVAL_MS);
    }

    /**
     * 向连接组添加一个携带增量协商参数的连接。
     *
     * @param connection           连接对象（SseEmitter）
     * @param deltaTargets         增量协商目标类型集（空 = 仅 buffered）
     * @param deltaFlushIntervalMs 增量刷写间隔毫秒（非正数收敛为缺省值）
     */
    public void addConnection(Object connection, Set<ChatEventType> deltaTargets, long deltaFlushIntervalMs) {
        if (closed.get()) {
            throw new IllegalStateException("连接句柄已关闭，无法添加新连接");
        }
        long interval = deltaFlushIntervalMs > 0
                ? deltaFlushIntervalMs : DEFAULT_DELTA_FLUSH_INTERVAL_MS;
        connections.put((SseEmitter) connection,
                new Negotiation(Set.copyOf(deltaTargets), interval));
    }

    @Override
    public void removeConnection(Object connection) {
        // 断连仅摘除本连接：MUST NOT 触发取消 / 中断（公开契约为持久化 turn 模型，
        // 客户端断线重连凭 Last-Event-ID 回放即可；取消只能由显式 POST /cancel 或 user.interrupt 发起）
        connections.remove(connection);
    }

    /**
     * 断连回调注册（无绑定空操作）。
     * <p>契约收敛（拆断连取消接线）：连接组断开不再携任何执行副作用，本方法保留仅为
     * {@link ConnectionHandle} 端口形状兼容，注册的回调被忽略。</p>
     */
    @Override
    public void onDisconnect(Runnable handler) {
        // no-op：断连不取消
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            connections.keySet().forEach(SseEmitter::complete);
            connections.clear();
        }
    }

    /**
     * 句柄是否已关闭。
     *
     * @return true=已关闭
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 心跳保活：对全部存活 emitter 发送注释行并探测死连接，失败则清理。
     * <p>由 {@link SseKeepAliveScheduler} 周期 tick 经句柄唯一所有者（会话运行时）调用，
     * 端口契约见 {@link ConnectionHandle#heartbeat()}。</p>
     */
    @Override
    public void heartbeat() {
        if (closed.get() || connections.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : connections.keySet()) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception ex) {
                removeConnection(emitter);
            }
        }
    }

    /** 向单个 emitter 发送 buffered 事件帧；发送失败即清理该连接并置错误。 */
    private void send(SseEmitter emitter, SseEventEnvelope envelope) {
        try {
            emitter.send(ChatEventCodec.toSseEvent(envelope));
        } catch (Exception ex) {
            removeConnection(emitter);
            emitter.completeWithError(ex);
        }
    }

    /** 向单个 emitter 发送流式增量帧；发送失败即清理该连接并置错误。 */
    private void sendFrame(SseEmitter emitter, StreamFrame frame) {
        try {
            emitter.send(ChatEventCodec.toStreamFrameEvent(frame));
        } catch (Exception ex) {
            removeConnection(emitter);
            emitter.completeWithError(ex);
        }
    }
}
