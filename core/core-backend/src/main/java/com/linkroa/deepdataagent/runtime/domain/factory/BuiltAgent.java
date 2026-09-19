package com.linkroa.deepdataagent.runtime.domain.factory;

/**
 * 已装配 Agent 的不透明句柄。
 * <p>领域层仅通过句柄调用生命周期操作，不暴露底层框架类型；
 * 实际包装的 {@code HarnessAgent} 仅存在于 infrastructure.client 实现中。</p>
 */
public interface BuiltAgent extends AutoCloseable {

    /**
     * 按会话身份定向中断当前执行（断连/中止会话时调用，幂等）。
     * <p>MUST 与执行时下发运行时的会话身份<b>同源</b>（同一 {@code userId}/{@code sessionId}
     * 对）：框架按 {@code (userId, sessionId)} 槽位隔离中断标志，传入不一致的身份等于
     * 打断另一个状态槽位——静默失效且不报错。<b>不保留无参重载</b>：框架无参
     * {@code interrupt()} 已废弃且打在 {@code defaultSessionId}（= builder.name）幽灵槽位上。</p>
     *
     * @param userId    会话用户身份（与执行时 RuntimeContext 同值）
     * @param sessionId 会话 ID（与执行时 RuntimeContext 同值）
     */
    void interrupt(String userId, String sessionId);

    /**
     * 释放 Agent 实例（会话终止/执行结束清理）。
     */
    @Override
    void close();
}