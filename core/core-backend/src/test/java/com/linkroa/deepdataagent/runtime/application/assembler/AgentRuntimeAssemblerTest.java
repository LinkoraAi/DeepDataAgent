package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentRuntimeAssembler} 会话/规格装配单测。
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

    @Test
    void should_buildSpecFromProperties_when_toSpec_given_sessionAndProperties() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setModelId("dashscope:qwen-max");
        properties.setSystemPrompt("你是数据分析专家");
        properties.setMaxIters(30);
        properties.setSandboxImage("python:3.12");
        properties.setSandboxMemoryBytes(8192L);
        properties.setSandboxCpuCount(4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(session, properties, Set.of());

        // then
        assertEquals("agent-a", spec.agentId());
        assertEquals("agent-" + "agent-a", spec.name());
        assertEquals("dashscope:qwen-max", spec.model());
        assertEquals("你是数据分析专家", spec.systemPrompt());
        assertEquals(30, spec.maxIters());
        assertEquals("python:3.12", spec.sandbox().image());
        assertEquals(8192L, spec.sandbox().memoryBytes());
        assertEquals(4L, spec.sandbox().cpuCount());
        assertTrue(spec.toolNames().isEmpty());
    }

    @Test
    void should_injectSortedToolNames_when_toSpec_given_availableToolSet() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        Set<String> toolNames = Set.of("calculator", "echo", "current_time");

        // when
        AgentAssemblySpec spec = assembler.toSpec(session, properties, toolNames);

        // then（工具名排序注入，保证装配确定性）
        assertEquals(List.of("calculator", "current_time", "echo"), spec.toolNames());
    }

    @Test
    void should_applyDefaultProperties_when_toSpec_given_defaultProperties() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{}", null);
        AgentRuntimeProperties properties = new AgentRuntimeProperties();

        // when
        AgentAssemblySpec spec = assembler.toSpec(session, properties, Set.of());

        // then
        assertEquals("dashscope:qwen-plus", spec.model());
        assertEquals(20, spec.maxIters());
        assertEquals("ubuntu:22.04", spec.sandbox().image());
    }
}