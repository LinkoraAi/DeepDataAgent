package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * {@link BuiltAgent} 的 Harness 实现：包装 AgentScope {@link HarnessAgent}。
 * <p>仅存在于 infrastructure.client 层，领域/应用层经 {@link BuiltAgent} 不透明句柄交互。</p>
 */
public final class HarnessBuiltAgent implements BuiltAgent {

    private final HarnessAgent delegate;

    /**
     * 包装底层 HarnessAgent，作为领域 / 应用层可见的不透明句柄。
     *
     * @param delegate AgentScope HarnessAgent 实例
     */
    public HarnessBuiltAgent(HarnessAgent delegate) {
        this.delegate = delegate;
    }

    /**
     * 按会话身份定向中断（委托 {@code HarnessAgent.interrupt(userId, sessionId)}，幂等）。
     * <p>2.0.3 起按 {@code (userId, sessionId)} 槽位隔离；不再委托废弃的无参
     * {@code interrupt()}（其打在 defaultSessionId 幽灵槽位上，升级后静默失效）。</p>
     */
    @Override
    public void interrupt(String userId, String sessionId) {
        delegate.interrupt(userId, sessionId);
    }

    /**
     * 底层 HarnessAgent（供执行器等基础设施实现使用）。
     */
    public HarnessAgent harness() {
        return delegate;
    }

    @Override
    public void close() {
        delegate.close();
    }
}