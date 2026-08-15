package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * {@link BuiltAgent} 的 Harness 实现：包装 AgentScope {@link HarnessAgent}。
 * <p>仅存在于 infrastructure.client 层，领域/应用层经 {@link BuiltAgent} 不透明句柄交互。</p>
 */
public final class HarnessBuiltAgent implements BuiltAgent {

    private final String agentId;
    private final HarnessAgent delegate;

    public HarnessBuiltAgent(String agentId, HarnessAgent delegate) {
        this.agentId = agentId;
        this.delegate = delegate;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    /**
     * 中断当前执行（委托 {@code HarnessAgent.interrupt()}，幂等）。
     */
    @Override
    public void interrupt() {
        delegate.interrupt();
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