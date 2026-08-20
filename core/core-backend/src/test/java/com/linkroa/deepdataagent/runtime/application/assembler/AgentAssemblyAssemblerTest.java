package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.agent.application.contract.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.Skill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentAssemblyAssembler} 运行时装配防腐映射单测（agent 契约 DTO → 本 BC 领域模型）。
 */
class AgentAssemblyAssemblerTest {

    private final AgentAssemblyAssembler assembler = new AgentAssemblyAssemblerImpl();

    @Test
    void should_mapContractToSpec_when_toSpec_given_validDto() {
        // given
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 3, "v3", "台账系统提示词", "openai:gpt-4",
                10, "sk-plain", "https://api.example.com/v1", List.of(1L, 2L), List.of());
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of());

        // then（契约字段按运行时语义映射）
        assertEquals("agent-a", spec.agentId());
        assertEquals("v3", spec.name());
        assertEquals("openai:gpt-4", spec.model());
        assertEquals("台账系统提示词", spec.systemPrompt());
        assertEquals(10, spec.maxIters());
        assertSame(sandbox, spec.sandbox());
        assertEquals("sk-plain", spec.credential());
        assertEquals("https://api.example.com/v1", spec.apiEndpointUrl());
        assertEquals(List.of(1L, 2L), spec.dataSourceIds());
    }

    @Test
    void should_passThroughSkills_when_toSpec_given_skills() {
        // given
        Skill skill = new Skill("code-reviewer", "代码评审", "指令正文", Map.of());
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(skill));

        // then
        assertEquals(List.of(skill), spec.skills());
    }

    @Test
    void should_reject_when_toSpec_given_nullDto() {
        // given & when & then（空契约导致映射字段为空，规格不变量拒绝空 agentId）
        assertThrows(IllegalArgumentException.class, () -> assembler.toSpec(
                null, AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L), List.of()));
    }

    @Test
    void should_reject_when_toSpec_given_nullSandbox() {
        // given & when & then（沙箱规格缺失，规格构造拒绝）
        assertThrows(IllegalArgumentException.class, () -> assembler.toSpec(sampleDto(), null, List.of()));
    }

    private ResolvedAgentAssemblyDTO sampleDto() {
        return new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "提示词", "openai:gpt-4",
                10, null, "https://api.example.com/v1", List.of(), List.of());
    }
}