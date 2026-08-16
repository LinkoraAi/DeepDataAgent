package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.agent.application.contract.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentAssemblyAssembler} 运行时装配防腐映射单测（agent 契约 DTO → 本 BC 领域模型）。
 * <p>覆盖字段语义映射、描述派生，以及装配规格本身对空源参数的防御性校验。</p>
 */
class AgentAssemblyAssemblerTest {

    private final AgentAssemblyAssembler assembler = new AgentAssemblyAssemblerImpl();

    @Test
    void should_mapContractToSpec_when_toSpec_given_validDto() {
        // given
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 3, "v3", "台账系统提示词", "openai:gpt-4",
                10, "sk-plain", "https://api.example.com/v1");
        List<String> tools = List.of("echo", "calculator");
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, tools, sandbox);

        // then（契约字段按运行时语义映射，描述由发布号派生）
        assertEquals("agent-a", spec.agentId());
        assertEquals("v3", spec.name());
        assertEquals("DeepDataAgent 装配的 Agent（发布号 3）", spec.description());
        assertEquals("openai:gpt-4", spec.model());
        assertEquals("台账系统提示词", spec.systemPrompt());
        assertEquals(tools, spec.toolNames());
        assertEquals(10, spec.maxIters());
        assertSame(sandbox, spec.sandbox());
    }

    @Test
    void should_reject_when_toSpec_given_nullDto() {
        // given & when & then（空契约在字段访问时即拒绝，不进入规格构造）
        assertThrows(NullPointerException.class, () -> assembler.toSpec(
                null, List.of(), AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L)));
    }

    @Test
    void should_reject_when_toSpec_given_nullToolNames() {
        // given & when & then（工具名集合缺失，规格构造拒绝）
        assertThrows(IllegalArgumentException.class, () -> assembler.toSpec(
                sampleDto(), null, AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L)));
    }

    @Test
    void should_reject_when_toSpec_given_nullSandbox() {
        // given & when & then（沙箱规格缺失，规格构造拒绝）
        assertThrows(IllegalArgumentException.class, () -> assembler.toSpec(sampleDto(), List.of(), null));
    }

    private ResolvedAgentAssemblyDTO sampleDto() {
        return new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "提示词", "openai:gpt-4",
                10, null, "https://api.example.com/v1");
    }
}