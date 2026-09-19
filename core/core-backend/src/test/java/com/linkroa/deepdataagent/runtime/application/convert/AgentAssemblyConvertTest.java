package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.agent.application.dto.AgentToolPolicyDTO;
import com.linkroa.deepdataagent.agent.application.dto.AgentToolVisibilityDTO;
import com.linkroa.deepdataagent.agent.application.dto.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.MemoryStoreRef;
import com.linkroa.deepdataagent.runtime.domain.model.Skill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentAssemblyConvert} 运行时装配防腐映射单测（agent 契约 DTO → 本 BC 领域模型）。
 */
class AgentAssemblyConvertTest {

    private final AgentAssemblyConvert assembler = AgentAssemblyConvert.INSTANCE;

    @Test
    void should_mapContractToSpec_when_toSpec_given_validDto() {
        // given
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 3, "v3", "台账系统提示词", "---\nname: 台账\ndescription: 客户台账助手\n---\n\n统一使用中文回复。",
                "openai:gpt-4",
                10, "sk-plain", "https://api.example.com/v1", List.of(), null, null,
                List.of());
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), Map.of(),
                "sess_1", 7L, List.of(), List.of());

        // then（契约字段按运行时语义映射）
        assertEquals("agent-a", spec.agentId());
        assertEquals("v3", spec.name());
        assertEquals("openai:gpt-4", spec.model());
        assertEquals("台账系统提示词", spec.systemPrompt());
        // AGENTS.md 原文逐字透传，供工厂物化到工作区根（不做二次加工）
        assertEquals("---\nname: 台账\ndescription: 客户台账助手\n---\n\n统一使用中文回复。", spec.agentsMd());
        assertEquals(10, spec.maxIters());
        assertSame(sandbox, spec.sandbox());
        assertEquals("sk-plain", spec.credential());
        assertEquals("https://api.example.com/v1", spec.apiEndpointUrl());
    }

    @Test
    void should_passThroughSkills_when_toSpec_given_skills() {
        // given
        Skill skill = new Skill("code-reviewer", "代码评审", "指令正文", Map.of());
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(skill), List.of(), List.of(), Map.of(),
                "sess_1", 7L, List.of(), List.of());

        // then
        assertEquals(List.of(skill), spec.skills());
    }

    @Test
    void should_passThroughMemoryStoreRefs_when_toSpec_given_memoryStoreRefs() {
        // given
        MemoryStoreRef ref = new MemoryStoreRef("ms-1", "会话记忆");
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(ref), List.of(), Map.of(),
                "sess_1", 7L, List.of(), List.of());

        // then
        assertEquals(List.of(ref), spec.memoryStoreRefs());
    }

    @Test
    void should_normalizeEmptyMemoryStoreRefs_when_toSpec_given_emptyMemoryStoreRefs() {
        // given
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), null, List.of(), Map.of(),
                "sess_1", 7L, List.of(), List.of());

        // then（空记忆引用归一化为空列表）
        assertTrue(spec.memoryStoreRefs().isEmpty());
    }

    @Test
    void should_keepAgentsMdNull_when_toSpec_given_dtoWithoutAgentsMd() {
        // given（未配置 AGENTS.md 的版本：契约该槽位为 null，规格同样为 null）
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), Map.of(),
                "sess_1", 7L, List.of(), List.of());

        // then
        assertNull(spec.agentsMd());
    }

    @Test
    void should_reject_when_toSpec_given_nullDto() {
        // given & when & then（空契约导致映射字段为空，规格不变量拒绝空 agentId）
        assertThrows(IllegalArgumentException.class, () -> assembler.toSpec(
                null, AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L),
                List.of(), List.of(), List.of(), Map.of(), "sess_1", 7L, List.of(), List.of()));
    }

    @Test
    void should_reject_when_toSpec_given_nullSandbox() {
        // given & when & then（沙箱规格缺失，规格构造拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> assembler.toSpec(sampleDto(), null, List.of(), List.of(), List.of(), Map.of(),
                        "sess_1", 7L, List.of(), List.of()));
    }

    @Test
    void should_passThroughVaultCredentials_when_toSpec_given_vaultCredentials() {
        // given
        AgentAssemblySpec.VaultCredentialRef ref = new AgentAssemblySpec.VaultCredentialRef(
                "vault-1", "cr-1", "static_bearer", "https://db.example.com", "sk-plain");
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(ref), Map.of(),
                "sess_1", 7L, List.of(), List.of());

        // then（保管库凭证引用原样透传进装配规格）
        assertEquals(List.of(ref), spec.vaultCredentials());
    }

    @Test
    void should_passThroughFileMounts_when_toSpec_given_reconciledView() {
        // given（解析期 reconcile 产出的挂载视图：防腐层原样透传进规格，供工厂拼清单与 bind mount）
        AgentAssemblySpec.FileMountRef mount = new AgentAssemblySpec.FileMountRef(
                "file_1", "sales.csv", 2048L, "mounts/dataset/sales.csv");
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), Map.of(),
                "sess_1", 7L, List.of(mount), List.of());

        // then
        assertEquals(List.of(mount), spec.fileMounts());
    }

    @Test
    void should_passThroughMcpConnections_when_toSpec_given_mcpConnections() {
        // given（版本声明的 MCP 连接清单：防腐层原样透传至顶层组件，供工厂装配 ToolsConfig.mcpServers）
        AgentAssemblySpec.McpConnection connection = new AgentAssemblySpec.McpConnection(
                "github", "https://api.githubcopilot.com/mcp");
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), Map.of(),
                "sess_1", 7L, List.of(), List.of(connection));

        // then
        assertEquals(List.of(connection), spec.mcpConnections());
    }

    @Test
    void should_defaultEmptyMcpConnections_when_toSpec_given_nullMcpConnections() {
        // given（未显式给出连接清单：规格紧凑构造器归一为空）
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), Map.of(),
                "sess_1", 7L, List.of(), null);

        // then
        assertTrue(spec.mcpConnections().isEmpty());
    }

    @Test
    void should_passThroughModelTuning_when_toSpec_given_dtoWithTuning() {
        // given（装配期解析的生效调优参数：防腐层原样透传，供工厂注入 Harness 模型配置）
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "提示词", null, "openai:gpt-4",
                10, null, "https://api.example.com/v1", List.of(), null, null,
                List.of(), "high", 200000);
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), Map.of(),
                "sess_1", 7L, List.of(), List.of());

        // then
        assertEquals("high", spec.modelEffort());
        assertEquals(200000, spec.modelContextWindow());
    }

    @Test
    void should_passThroughEnvironmentVariables_when_toSpec_given_sessionEnvVars() {
        // given（Session 挂载解析出的会话环境变量：防腐层原样透传，供工厂注入沙箱数据面）
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);
        Map<String, String> envVars = Map.of("LOG_LEVEL", "debug", "TZ", "Asia/Shanghai");

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), envVars,
                "sess_1", 7L, List.of(), List.of());

        // then
        assertEquals(envVars, spec.environmentVariables());
    }

    @Test
    void should_normalizeEmptyEnvVars_when_toSpec_given_nullEnvironmentVariables() {
        // given（无会话环境变量：规格紧凑构造器归为空映射）
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), null,
                "sess_1", 7L, List.of(), List.of());

        // then
        assertTrue(spec.environmentVariables().isEmpty());
    }

    @Test
    void should_mapToolPolicies_when_toSpec_given_dtoWithPolicies() {
        // given（契约逐工具权限策略：防腐层映射为本 BC 值对象，供工厂装配权限引擎）
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "提示词", null, "openai:gpt-4",
                10, null, "https://api.example.com/v1", List.of(), null, null,
                List.of(), null, null,
                List.of(new AgentToolPolicyDTO("Bash", AgentToolPolicyDTO.POLICY_ALWAYS_ASK),
                        new AgentToolPolicyDTO("WebFetch", AgentToolPolicyDTO.POLICY_ALWAYS_DENY)));
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), Map.of(),
                "sess_1", 7L, List.of(), List.of());

        // then（同名组件逐条映射，保持声明顺序）
        assertEquals(2, spec.toolPolicies().size());
        assertEquals("Bash", spec.toolPolicies().get(0).name());
        assertEquals("always_ask", spec.toolPolicies().get(0).permissionPolicy());
        assertEquals("WebFetch", spec.toolPolicies().get(1).name());
        assertEquals("always_deny", spec.toolPolicies().get(1).permissionPolicy());
    }

    @Test
    void should_defaultEmptyToolPolicies_when_toSpec_given_dtoWithoutPolicies() {
        // given（旧形态契约无策略槽位：规格归一为空列表）
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), Map.of(),
                "sess_1", 7L, List.of(), List.of());

        // then
        assertTrue(spec.toolPolicies().isEmpty());
    }

    @Test
    void should_passThroughArtifactContext_when_toSpec_given_sessionIdAndOwnerId() {
        // given（产出归属上下文由 Session 装配现场透传，供交付工具登记）
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), Map.of(),
                "sess_artifact_9", 42L, List.of(), List.of());

        // then
        assertEquals("sess_artifact_9", spec.sessionId());
        assertEquals(42L, spec.ownerId());
    }

    @Test
    void should_keepArtifactContextNull_when_toSpec_given_nullSessionIdAndOwnerId() {
        // given（未绑定会话的装配现场：归属槽位允许为空，交付时由登记面拒绝）
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), Map.of(),
                null, null, List.of(), List.of());

        // then
        assertNull(spec.sessionId());
        assertNull(spec.ownerId());
    }

    @Test
    void should_mapToolVisibility_when_toSpec_given_dtoWithVisibility() {
        // given（契约可见性名单映射为本 BC 值对象，契约名原样透传不翻译）
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "提示词", null, "openai:gpt-4",
                10, null, "https://api.example.com/v1", List.of(), null, null,
                List.of(), null, null, List.of(),
                new AgentToolVisibilityDTO(List.of("Bash", "Read"), List.of("Write")));
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), Map.of(),
                "sess_1", 7L, List.of(), List.of());

        // then
        assertTrue(spec.toolVisibility().hasConstraint());
        assertEquals(List.of("Bash", "Read"), spec.toolVisibility().allowedTools());
        assertEquals(List.of("Write"), spec.toolVisibility().hiddenTools());
    }

    @Test
    void should_defaultNoVisibilityConstraint_when_toSpec_given_dtoWithoutVisibility() {
        // given（旧形态契约无可见性槽位：规格归一为无约束）
        ResolvedAgentAssemblyDTO dto = sampleDto();
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L);

        // when
        AgentAssemblySpec spec = assembler.toSpec(dto, sandbox, List.of(), List.of(), List.of(), Map.of(),
                "sess_1", 7L, List.of(), List.of());

        // then
        assertFalse(spec.toolVisibility().hasConstraint());
    }

    private ResolvedAgentAssemblyDTO sampleDto() {
        return new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "提示词", null, "openai:gpt-4",
                10, null, "https://api.example.com/v1", List.of(), null, null,
                List.of());
    }
}
