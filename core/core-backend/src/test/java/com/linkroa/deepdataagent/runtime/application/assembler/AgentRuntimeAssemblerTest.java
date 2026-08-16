package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link AgentRuntimeAssembler} 会话装配单测。
 * <p>复杂度装配 {@code AgentAssemblySpec} 已下沉至
 * {@code RuntimeAgentAssemblyResolver}，此处仅验证命令 → 会话领域对象映射。</p>
 */
class AgentRuntimeAssemblerTest {

    private final AgentRuntimeAssembler assembler = new AgentRuntimeAssemblerImpl();

    @Test
    void should_buildIdleSession_when_toSession_given_validCommand() {
        // given
        CreateSessionCommand command = new CreateSessionCommand("u-1", "agent-a", "1.0.0", "会话", "{}");

        // when
        AgentSession session = assembler.toSession(command);

        // then
        assertEquals("u-1", session.userId());
        assertEquals("agent-a", session.agentId());
        assertEquals("1.0.0", session.agentVersion());
        assertEquals("会话", session.title());
        assertEquals(AgentSessionStatus.IDLE, session.status());
        assertNotEquals(command.userId(), session.sessionId());
    }
}