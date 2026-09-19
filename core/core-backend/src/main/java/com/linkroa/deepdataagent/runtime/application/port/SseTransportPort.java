package com.linkroa.deepdataagent.runtime.application.port;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;

/**
 * SSE 传输工厂端口（进程内出站端口，实现为 {@code infrastructure.sse.SseTransportFactory}）。
 * <p>订阅编排（{@code SessionSubscriptionService}）经本端口触达 SSE 协议细节：句柄获取 / 重建、
 * 携带增量协商参数的连接注册、注释帧 / buffered 事件 / 流式帧的单连接下发——
 * 应用层 MUST NOT 直接 import {@code infrastructure.sse} 下的具体类（分层铁律）。</p>
 * <p>SSE 协议信封 / 帧编码（{@code ChatEventCodec}）与 emitter 生命周期回调接线全部收在实现侧；
 * 返回值 / 入参中的 {@link SseEmitter} 是控制器出口唯一接触的框架类型
 * （与 {@code AgentRunExecutor} 端口以 reactor {@code Flux} 表达流同类的技术载体先例）。</p>
 */
public interface SseTransportPort {

    /** 增量刷写间隔缺省值（毫秒），对齐 {@code delta_flush_interval_ms} 契约缺省。 */
    long DEFAULT_DELTA_FLUSH_INTERVAL_MS = 50L;

    /**
     * 获取会话可用的连接句柄：已绑定且仍存活的 SSE 句柄直接复用（多订阅者 fan-out 共享连接组），
     * 否则（NoOp / 已关闭 / 非 SSE 实现）新建一个。句柄唯一由会话运行时持有，工厂不自持映射。
     *
     * @param bound 会话上下文当前绑定的连接句柄（可为 {@code NoOpConnectionHandle}）
     * @return 可直接绑定到会话上下文的活跃 SSE 连接句柄
     */
    ConnectionHandle acquireHandle(ConnectionHandle bound);

    /**
     * 在句柄连接组上注册一个携带连接级增量协商参数的新连接。
     *
     * @param handle               会话连接句柄（{@link #acquireHandle} 的返回值）
     * @param deltaTargets         增量协商目标类型集（空 = 仅 buffered 完整事件）
     * @param deltaFlushIntervalMs 增量刷写间隔毫秒（非正数收敛为缺省 {@value #DEFAULT_DELTA_FLUSH_INTERVAL_MS}ms）
     * @return 已完成超时 / 断连回调接线（失败即自清连接组条目）的 emitter
     */
    SseEmitter openConnection(ConnectionHandle handle, Set<ChatEventType> deltaTargets, long deltaFlushIntervalMs);

    /**
     * 向单个连接发送注释帧（SSE {@code ": <comment>"}，无数据语义；连接握手 {@code connected} 行）。
     */
    void sendComment(SseEmitter connection, String comment) throws IOException;

    /**
     * 向单个连接发送一条 buffered 事件（回放路径：领域事件 → 信封 → SSE 帧编码在实现侧完成）。
     */
    void sendEvent(SseEmitter connection, ChatEvent event) throws IOException;

    /**
     * 向单个连接发送一帧流式增量帧（{@code event_start} / {@code event_delta}，一段重连回补路径）。
     */
    void sendFrame(SseEmitter connection, StreamFrame frame) throws IOException;
}
