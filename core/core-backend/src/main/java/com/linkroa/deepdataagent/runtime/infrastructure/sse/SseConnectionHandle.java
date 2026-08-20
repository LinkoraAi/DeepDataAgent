package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.application.assembler.SseEventEnvelopeAssembler;
import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.ConnectionHandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSE 连接句柄 —— 领域端口 {@link ConnectionHandle} 的进程内 SSE 适配实现。
 * <p>表达「一个会话对应一组连接」的多订阅者 fan-out 语义：内部持有该会话的
 * {@link SseEmitter} 连接组，{@link #push} 将领域事件经 {@link SseEventEnvelopeAssembler}
 * 转换为 {@code SseEventEnvelope} 后广播；协议细节（信封 → SSE 帧）完全不泄漏到领域层。</p>
 * <ul>
 *   <li>{@link #removeConnection} 检测到最后一个连接断开时触发 {@link #onDisconnect} 注册的回调；</li>
 *   <li>{@link #close} 完成全部订阅者（会话终止 / 句柄替换时经
 *       {@code AgentSessionContext.bindConnection} 触发旧句柄关闭）。</li>
 * </ul>
 * <p>同一会话的 {@link #push} 由事件全序保证串行；内部仅用 {@link ConcurrentHashMap} 键集
 * 承载连接组以兼容心跳 / 断连回调的并发访问。</p>
 */
@Slf4j
public class SseConnectionHandle implements ConnectionHandle {

    /** 会话连接组（fan-out 目标集合）。 */
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    /** 领域 → 信封协议转换装配器（复用既有实现）。 */
    private final SseEventEnvelopeAssembler envelopeAssembler;
    /** 全部连接断开时触发的一次性回调。 */
    private final AtomicReference<Runnable> disconnectHandler = new AtomicReference<>();
    /** 句柄是否已关闭（关闭后拒绝新增连接、push 为空操作）。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 构造 SSE 连接句柄。
     *
     * @param envelopeAssembler 领域事件 → 对外信封的装配器
     */
    public SseConnectionHandle(SseEventEnvelopeAssembler envelopeAssembler) {
        this.envelopeAssembler = envelopeAssembler;
    }

    @Override
    public void push(ChatEvent event) {
        pushExcluding(event, Set.of());
    }

    @Override
    public void pushExcluding(ChatEvent event, Set<?> excludedConnections) {
        if (closed.get() || emitters.isEmpty()) {
            return;
        }
        SseEventEnvelope envelope = envelopeAssembler.toEnvelope(event);
        for (SseEmitter emitter : emitters) {
            if (excludedConnections.contains(emitter)) {
                continue;
            }
            send(emitter, envelope);
        }
    }

    @Override
    public void addConnection(Object connection) {
        if (closed.get()) {
            throw new IllegalStateException("连接句柄已关闭，无法添加新连接");
        }
        emitters.add((SseEmitter) connection);
    }

    @Override
    public void removeConnection(Object connection) {
        emitters.remove(connection);
        if (emitters.isEmpty()) {
            // 连接组全部断开：触发断连回调（由绑定方注册为取消在跑执行）
            Runnable handler = disconnectHandler.get();
            if (handler != null) {
                handler.run();
            }
        }
    }

    @Override
    public void onDisconnect(Runnable handler) {
        disconnectHandler.set(handler);
    }

    @Override
    public boolean isActive() {
        return !closed.get() && !emitters.isEmpty();
    }

    @Override
    public int activeCount() {
        return emitters.size();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            emitters.forEach(SseEmitter::complete);
            emitters.clear();
            disconnectHandler.set(null);
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
     * <p>由全局心跳调度器调用（见 {@link SseEmitterRegistry}）。</p>
     */
    void heartbeat() {
        if (closed.get() || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception ex) {
                removeConnection(emitter);
            }
        }
    }

    /** 向单个 emitter 发送帧；发送失败即清理该连接并置错误。 */
    private void send(SseEmitter emitter, SseEventEnvelope envelope) {
        try {
            emitter.send(ChatEventCodec.toSseEvent(envelope));
        } catch (Exception ex) {
            removeConnection(emitter);
            emitter.completeWithError(ex);
        }
    }
}