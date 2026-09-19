package com.linkroa.deepdataagent.runtime.domain.port;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;

import java.util.Set;

/**
 * 空操作连接句柄 —— {@link com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext}
 * 创建时连接层的默认值。
 * <p>在 SSE 连接建立或同步模式触发前，连接层使用此实现：{@code push} 为空操作，
 * 避免 null 检查。</p>
 */
public final class NoOpConnectionHandle implements ConnectionHandle {

    /** 共享单例。 */
    public static final NoOpConnectionHandle INSTANCE = new NoOpConnectionHandle();

    private NoOpConnectionHandle() {
    }

    @Override
    public void push(ChatEvent event) {
        // no-op
    }

    @Override
    public void pushExcluding(ChatEvent event, Set<?> excludedConnections) {
        // no-op
    }

    @Override
    public void removeConnection(Object connection) {
        // no-op
    }

    @Override
    public void onDisconnect(Runnable handler) {
        // no-op
    }
}