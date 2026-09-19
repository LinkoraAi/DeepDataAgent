package com.linkroa.deepdataagent.runtime.infrastructure.sse;

import com.linkroa.deepdataagent.runtime.application.convert.SseEventEnvelopeConvert;
import com.linkroa.deepdataagent.runtime.application.port.SseTransportPort;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;

/**
 * 会话级 SSE 传输工厂（{@link SseTransportPort} 的实现，原 {@code SseEmitterRegistry} 收敛为纯工厂）。
 * <p>不再自持 sessionId→handle 映射（D18 句柄双主持有消除）：句柄唯一由
 * {@code AgentSessionContext}（会话运行时）持有，本类只负责「复用判定 / new / 连接注册 /
 * 协议帧下发」；全局保活心跳见 {@link SseKeepAliveScheduler}，死连接回收走句柄既有
 * {@code send} 失败清理路径。</p>
 */
@Component
public class SseTransportFactory implements SseTransportPort {

    /** SSE 超时直读配置类（配置类直注，不再经端口包装）。 */
    @Resource
    private AgentRuntimeProperties runtimeProperties;

    @Override
    public ConnectionHandle acquireHandle(ConnectionHandle bound) {
        // 已绑定且存活的 SSE 句柄直接复用（多订阅者 fan-out 共享连接组）；NoOp / 已关闭则重建
        if (bound instanceof SseConnectionHandle handle && !handle.isClosed()) {
            return handle;
        }
        return new SseConnectionHandle();
    }

    @Override
    public SseEmitter openConnection(ConnectionHandle handle, Set<ChatEventType> deltaTargets,
                                     long deltaFlushIntervalMs) {
        if (!(handle instanceof SseConnectionHandle connectionHandle)) {
            throw new IllegalArgumentException("openConnection 仅接受 SseConnectionHandle 句柄");
        }
        SseEmitter emitter = new SseEmitter(runtimeProperties.getSseTimeout().toMillis());
        connectionHandle.addConnection(emitter, deltaTargets, deltaFlushIntervalMs);
        Runnable cleanup = () -> connectionHandle.removeConnection(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    @Override
    public void sendComment(SseEmitter connection, String comment) throws IOException {
        connection.send(SseEmitter.event().comment(comment));
    }

    @Override
    public void sendEvent(SseEmitter connection, ChatEvent event) throws IOException {
        connection.send(ChatEventCodec.toSseEvent(SseEventEnvelopeConvert.INSTANCE.toEnvelope(event)));
    }

    @Override
    public void sendFrame(SseEmitter connection, StreamFrame frame) throws IOException {
        connection.send(ChatEventCodec.toStreamFrameEvent(frame));
    }
}
