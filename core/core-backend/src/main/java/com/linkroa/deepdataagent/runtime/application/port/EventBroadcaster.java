package com.linkroa.deepdataagent.runtime.application.port;

import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;

/**
 * 会话事件广播出站端口（依赖倒置）。
 * <p>应用层通过本端口推送已装配为 {@link SseEventEnvelope} 的对外信封；
 * 进程内实现为 {@code infrastructure.sse.SseEmitterRegistry}（单实例首版），
 * 未来多实例可替换为 Redis pub/sub 实现而不影响用例编排。</p>
 */
public interface EventBroadcaster {

    /**
     * 广播事件信封至会话的全部订阅者。
     *
     * @param sessionId 会话 ID
     * @param envelope  已装配的对外事件信封
     */
    void broadcast(String sessionId, SseEventEnvelope envelope);

    /**
     * 完成会话的全部订阅者（会话终止 / 执行结束清理）。
     *
     * @param sessionId 会话 ID
     */
    void complete(String sessionId);
}