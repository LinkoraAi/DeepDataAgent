package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link AgentRuntimeConvert} 会话装配单测。
 * <p>复杂度装配 {@code AgentAssemblySpec} 已下沉至
 * {@code RuntimeAgentAssemblyService}，此处仅验证命令 → 会话领域对象映射。</p>
 */
class AgentRuntimeConvertTest {

    private final AgentRuntimeConvert assembler = AgentRuntimeConvert.INSTANCE;

    @Test
    void should_buildCreatedSession_when_toSession_given_validCommand() {
        // given
        CreateSessionCommand command = new CreateSessionCommand("u-1", "agent-a", "1.0.0", "会话", "{}");

        // when
        AgentSession session = assembler.toSession(command);

        // then（双字段状态机新契约：新会话初始 idle / idle，旧 created 初始态已并入 idle）
        assertEquals("u-1", session.userId());
        assertEquals("agent-a", session.agentId());
        assertEquals("1.0.0", session.agentVersion());
        assertEquals("会话", session.title());
        assertEquals(AgentSessionStatus.IDLE, session.status());
        assertEquals(TurnPhase.IDLE, session.turnPhase());
        assertNotEquals(command.userId(), session.sessionId());
    }
}