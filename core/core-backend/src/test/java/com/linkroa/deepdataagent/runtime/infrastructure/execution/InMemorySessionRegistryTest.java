package com.linkroa.deepdataagent.runtime.infrastructure.execution;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemorySessionRegistry} 会话上下文注册表单测（原子创建幂等 + 生命周期）。
 */
class InMemorySessionRegistryTest {

    private InMemorySessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemorySessionRegistry();
    }

    @Test
    void should_createOnce_when_getOrCreate_given_repeatedSameSession() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);

        // when
        AgentSessionContext first = registry.getOrCreate(session);
        AgentSessionContext second = registry.getOrCreate(session);

        // then（computeIfAbsent 原子创建：同会话幂等返回既有实例，不覆盖常驻序号/状态）
        assertSame(first, second);
        assertEquals(session.sessionId(), first.sessionId());
    }

    @Test
    void should_returnContext_when_get_given_createdSession() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
        registry.getOrCreate(session);

        // when
        AgentSessionContext context = registry.get(session.sessionId()).orElseThrow();

        // then
        assertEquals(session.sessionId(), context.sessionId());
    }

    @Test
    void should_returnEmpty_when_get_given_unregisteredSession() {
        // when & then（未创建会话不存在）
        assertFalse(registry.get("nope").isPresent());
    }

    @Test
    void should_removeContext_when_remove_given_createdSession() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
        registry.getOrCreate(session);

        // when
        registry.remove(session.sessionId());

        // then（生命周期：终止/清理后不可再获取）
        assertTrue(registry.get(session.sessionId()).isEmpty());
    }
}