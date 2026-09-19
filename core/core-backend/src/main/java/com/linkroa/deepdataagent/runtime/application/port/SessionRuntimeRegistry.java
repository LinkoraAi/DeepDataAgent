package com.linkroa.deepdataagent.runtime.application.port;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;

import java.util.Collection;
import java.util.Optional;

/**
 * 会话运行时聚合注册表端口（进程内会话上下文管理，依赖倒置）。
 * <p>以 {@code sessionId} 为键维护 {@link AgentSessionContext}（逻辑线程组）的
 * 生命周期：{@link #getOrCreate} 原子创建（同一会话幂等返回既有实例，不覆盖
 * 已常驻的镜像与序号），供应用服务在每轮执行前取得会话级聚合；实现位于基础设施层，
 * 多实例部署时可替换为分布式实现。</p>
 */
public interface SessionRuntimeRegistry {

    /**
     * 获取或原子创建会话上下文（同一会话幂等返回既有实例，不覆盖已常驻的镜像与序号）。
     *
     * @param session 会话镜像（仅首次创建时使用）
     * @return 该会话的进程内聚合实例
     */
    AgentSessionContext getOrCreate(AgentSession session);

    /**
     * 会话上下文（进程内当前存在时；不存在返回 {@link Optional#empty()} 且调用方需幂等处理）。
     */
    Optional<AgentSessionContext> get(String sessionId);

    /**
     * 移除会话上下文（会话终止 / 进程内清理）。
     */
    void remove(String sessionId);

    /**
     * 当前进程内存在的全部会话上下文<b>只读快照</b>（无写路径、无行为副作用）。
     * <p>供 SSE 保活心跳调度器（{@code SseKeepAliveScheduler}）遍历句柄唯一所有者
     * （{@code AgentSessionContext.connection()}）下发保活注释帧。</p>
     *
     * @return 活跃会话上下文集合（弱一致性视图）
     */
    Collection<AgentSessionContext> activeContexts();
}