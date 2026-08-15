package com.linkroa.deepdataagent.runtime.domain.event;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;

/**
 * 会话事件广播出向端口（依赖倒置）。
 * <p>应用层与领域层仅依赖本端口推送事件与结束订阅；
 * 进程内实现为 {@code infrastructure.sse.SseEmitterRegistry}（单实例首版），
 * 未来多实例可替换为 Redis pub/sub 实现而不影响用例编排。</p>
 */
public interface EventBroadcaster {

    /**
     * 广播事件至会话的全部订阅者。
     *
     * @param sessionId 会话 ID
     * @param event     已落库的领域聊天事件
     */
    void broadcast(String sessionId, ChatEvent event);

    /**
     * 完成会话的全部订阅者（会话终止 / 执行结束清理）。
     *
     * @param sessionId 会话 ID
     */
    void complete(String sessionId);
}