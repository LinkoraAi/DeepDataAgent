package com.linkroa.deepdataagent.runtime.domain.port;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.StreamFrame;

import java.util.Set;

/**
 * 连接层接口 —— 逻辑线程组的通信通道抽象（领域端口）。
 * <p>表达「一个 session 对应一组连接」的多订阅者 fan-out 语义（多标签页 / 多设备同时订阅）。
 * {@link #push} 写入领域事件 {@link ChatEvent}，由基础设施实现类负责转换为具体协议的帧格式
 * （如 {@code ChatEvent → SseEventEnvelope → SSE}）后推送；domain 层零框架依赖、不泄漏协议信封。</p>
 * <p><b>线程安全</b>：同一会话的 {@link #push} / {@link #pushExcluding} 由事件全序保证串行，
 * 实现内部不承诺跨线程并发安全。</p>
 */
public interface ConnectionHandle {

    /**
     * 将领域事件广播到连接组内所有活跃连接。
     *
     * @param event 待推送的会话领域事件
     */
    void push(ChatEvent event);

    /**
     * 广播领域事件，但排除指定连接集合（用于断线补发期间防乱序）。
     *
     * @param event                待推送的会话领域事件
     * @param excludedConnections  需排除的连接对象集合（引用相等比较）
     */
    void pushExcluding(ChatEvent event, Set<?> excludedConnections);

    /**
     * 从连接组移除一个连接。
     *
     * @param connection 要移除的连接对象
     */
    void removeConnection(Object connection);

    /**
     * 推送流式增量帧（{@code event_start} / {@code event_delta}）：仅实时下发、不落库不回放。
     * <p>增量帧为<b>连接级协商</b>能力：实现类仅向显式协商了 {@code frame.targetType()}
     * 的连接下发（默认未协商 = 仅 buffered 完整事件）。默认空操作——不参与增量协商的
     * 连接实现（如 {@link NoOpConnectionHandle}）无需覆写。</p>
     *
     * @param frame 待推送的流式帧
     */
    default void pushFrame(StreamFrame frame) {
        // no-op：默认连接组不接收增量帧
    }

    /**
     * 增量刷写间隔（毫秒）：连接组内协商了增量帧的连接的最短刷写间隔
     * （高频 delta 按此聚合刷写，缺省 50ms，对齐 {@code delta_flush_interval_ms}）。
     *
     * @return 刷写间隔毫秒数
     */
    default long deltaFlushIntervalMs() {
        return 50L;
    }

    /**
     * 注册断连回调（端口形状兼容保留，语义已收敛为无副作用）。
     * <p><b>断连不取消</b>：SSE 连接只是观察 / 回放通道，连接断开 MUST NOT 触发在跑执行取消
     * （取消只能由显式 {@code POST /cancel} 或 {@code user.interrupt} 发起，客户端断线重连凭
     * {@code Last-Event-ID} 回放即可）。实现方 SHOULD 将其视为无绑定空操作，MUST NOT 借该回调
     * 触发任何执行副作用。</p>
     *
     * @param handler 断连回调（实现方可忽略）
     */
    void onDisconnect(Runnable handler);

    /**
     * 关闭连接句柄，释放所有资源（心跳任务、活跃连接等）。
     * <p>当 {@code AgentSessionContext.bindConnection} 替换旧句柄时自动调用。
     * 默认空操作，有资源的实现类（如 SSE）应覆写。</p>
     */
    default void close() {
        // no-op
    }

    /**
     * 心跳保活：向连接组内全部存活连接发送注释保活帧（空闲期防容器 / 代理回收长连接，
     * WebMvc 7 超时固定不可续期，保活帧是唯一空闲期续命手段），发送失败即清理该连接。
     * <p>由 {@code SseKeepAliveScheduler} 周期 tick 遍历会话运行时注册表调用；
     * 默认空操作——无需保活的连接实现（如 {@link NoOpConnectionHandle}）不必覆写。</p>
     */
    default void heartbeat() {
        // no-op：默认连接组无保活语义
    }
}