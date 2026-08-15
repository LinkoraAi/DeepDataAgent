package com.linkroa.deepdataagent.runtime.domain.factory;

/**
 * 已装配 Agent 的不透明句柄。
 * <p>领域层仅通过句柄调用生命周期操作，不暴露底层框架类型；
 * 实际包装的 {@code HarnessAgent} 仅存在于 infrastructure.client 实现中。</p>
 */
public interface BuiltAgent extends AutoCloseable {

    /**
     * Agent 业务 ID。
     */
    String agentId();

    /**
     * 中断当前执行（断连/中止会话时调用，幂等）。
     */
    void interrupt();

    /**
     * 释放 Agent 实例（会话终止/执行结束清理）。
     */
    @Override
    void close();
}