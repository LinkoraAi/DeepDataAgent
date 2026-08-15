package com.linkroa.deepdataagent.runtime.infrastructure.execution;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内会话上下文注册表实现（{@link SessionRegistry}）。
 * <p>基于 {@link ConcurrentHashMap#computeIfAbsent} 原子创建：同会话并发取
 * {@link AgentSessionContext} 时仅首个请求创建实例，其余复用既有实例。
 * 仅承载「会话 → 逻辑线程组」的映射与生命周期；在跑执行 / 断连中断的语义
 * 全部收敛在 {@link AgentSessionContext} 实例内。</p>
 */
@Component
public class InMemorySessionRegistry implements SessionRegistry {

    private final Map<String, AgentSessionContext> contexts = new ConcurrentHashMap<>();

    @Override
    public AgentSessionContext getOrCreate(AgentSession session) {
        return contexts.computeIfAbsent(session.sessionId(), k -> new AgentSessionContext(session));
    }

    @Override
    public Optional<AgentSessionContext> get(String sessionId) {
        return Optional.ofNullable(contexts.get(sessionId));
    }

    @Override
    public void remove(String sessionId) {
        contexts.remove(sessionId);
    }
}