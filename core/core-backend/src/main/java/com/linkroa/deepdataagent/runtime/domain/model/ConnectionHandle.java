package com.linkroa.deepdataagent.runtime.domain.model;

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
     * 向连接组添加一个新连接。
     * <p>受 per-session 限额约束，超限时抛 {@link IllegalStateException}。</p>
     *
     * @param connection 连接对象（实现类自行解释类型，如 SseEmitter）
     */
    void addConnection(Object connection);

    /**
     * 从连接组移除一个连接。
     *
     * @param connection 要移除的连接对象
     */
    void removeConnection(Object connection);

    /**
     * 注册断连回调，当连接组内所有连接断开时触发。
     *
     * @param handler 断连回调
     */
    void onDisconnect(Runnable handler);

    /**
     * 查询连接组中是否仍有活跃连接。
     *
     * @return true=仍有活跃连接
     */
    boolean isActive();

    /**
     * 返回当前活跃连接数。
     *
     * @return 活跃连接数
     */
    int activeCount();

    /**
     * 关闭连接句柄，释放所有资源（心跳任务、活跃连接等）。
     * <p>当 {@code AgentSessionContext.bindConnection} 替换旧句柄时自动调用。
     * 默认空操作，有资源的实现类（如 SSE）应覆写。</p>
     */
    default void close() {
        // no-op
    }
}