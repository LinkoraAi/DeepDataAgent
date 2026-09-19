package com.linkroa.deepdataagent.agent.application.dto;

import com.linkroa.deepdataagent.agent.api.dto.EnvironmentReferenceDTO;
import com.linkroa.deepdataagent.memory.api.dto.MemoryStoreReferenceDTO;
import com.linkroa.deepdataagent.vault.application.dto.ResolvedVaultCredentialDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResolvedAgentAssemblyDTO} 装配契约边界校验单测。
 * <p>覆盖发布语言 DTO 的全部不变量：空值 / 非法数值 / 超长系统提示词，
 * 以及 environment / memory_store / vault 引用字段的可空 / 空归一化。</p>
 */
class ResolvedAgentAssemblyDTOTest {

    private static final int MAX_SYSTEM_LENGTH = 100000;

    private static final int MAX_AGENTS_MD_LENGTH = 20000;

    @Test
    void should_acceptValidContract_when_construct_given_allRequiredFields() {
        // given
        // when
        ResolvedAgentAssemblyDTO dto = sample();

        // then
        assertEquals("agent-a", dto.agentId());
        assertEquals(1, dto.versionNumber());
        assertEquals("v1", dto.versionName());
        assertEquals("你是数据分析专家", dto.sysPrompt());
        assertEquals("# 项目约定\n\n统一使用中文回复。", dto.agentsMd());
        assertEquals("openai:gpt-4", dto.modelIndicator());
        assertEquals(10, dto.maxIters());
        assertEquals("sk-plain", dto.credential());
        assertEquals("https://api.example.com/v1", dto.apiEndpointUrl());
        assertTrue(dto.skills().isEmpty());
        assertNull(dto.environment());
        assertTrue(dto.memoryStores().isEmpty());
        assertTrue(dto.vaultCredentials().isEmpty());
    }

    @Test
    void should_acceptMaxLengthSystem_when_construct_given_100000CharSystem() {
        // given（契约允许 100000 字符上限的系统提示词，对齐版本快照 system 上限）
        String system = "s".repeat(MAX_SYSTEM_LENGTH);

        // when & then
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", system, null, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, null, null, null);
        assertEquals(MAX_SYSTEM_LENGTH, dto.sysPrompt().length());
    }

    @Test
    void should_acceptMaxLengthAgentsMd_when_construct_given_20000CharAgentsMd() {
        // given（AGENTS.md 独立 20000 字符契约上限，对齐领域 AgentsMd 值对象）
        String agentsMd = "# 项目约定\n\n统一使用中文回复。";

        // when
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "system", agentsMd, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, null, null, null);

        // then
        assertEquals(agentsMd, dto.agentsMd());
    }

    @Test
    void should_reject_when_construct_given_oversizedAgentsMd() {
        // given（AGENTS.md 超出契约上限 20000 字符）
        String agentsMd = "m".repeat(MAX_AGENTS_MD_LENGTH + 1);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO("agent-a", 1, "v1", "system", agentsMd,
                        "openai:gpt-4", 10, null, "https://api.example.com/v1",
                        null, null, null, null));
    }

    @Test
    void should_reject_when_construct_given_blankAgentId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO(" ", 1, "v1", "system", null, "openai:gpt-4",
                        10, null, "https://api.example.com/v1", null, null, null, null));
    }

    @Test
    void should_reject_when_construct_given_zeroVersionNumber() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO("agent-a", 0, "v1", "system", null, "openai:gpt-4",
                        10, null, "https://api.example.com/v1", null, null, null, null));
    }

    @Test
    void should_reject_when_construct_given_blankVersionName() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO("agent-a", 1, "", "system", null, "openai:gpt-4",
                        10, null, "https://api.example.com/v1", null, null, null, null));
    }

    @Test
    void should_reject_when_construct_given_blankModelIndicator() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO("agent-a", 1, "v1", "system", null, "",
                        10, null, "https://api.example.com/v1", null, null, null, null));
    }

    @Test
    void should_reject_when_construct_given_zeroMaxIters() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO("agent-a", 1, "v1", "system", null, "openai:gpt-4",
                        0, null, "https://api.example.com/v1", null, null, null, null));
    }

    @Test
    void should_reject_when_construct_given_oversizedSystem() {
        // given（超出契约上限 100000 字符）
        String system = "s".repeat(MAX_SYSTEM_LENGTH + 1);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO("agent-a", 1, "v1", system, null, "openai:gpt-4",
                        10, null, "https://api.example.com/v1", null, null, null, null));
    }

    @Test
    void should_normalizeNullReferences_when_construct_given_nullEnvironmentAndMemory() {
        // given（环境可空、记忆库 / 保管库凭证空归一化为不可变空列表）
        // when
        ResolvedAgentAssemblyDTO dto = sample();

        // then
        assertNull(dto.environment());
        assertTrue(dto.memoryStores().isEmpty());
        assertTrue(dto.vaultCredentials().isEmpty());
    }

    @Test
    void should_keepReferences_when_construct_given_environmentAndMemory() {
        // given
        EnvironmentReferenceDTO environment = new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", "cloud", "pip install pandas");
        MemoryStoreReferenceDTO memory = new MemoryStoreReferenceDTO("mem-1", "会话记忆");
        ResolvedVaultCredentialDTO vaultCredential = new ResolvedVaultCredentialDTO(
                "vault-1", "cr-1", "static_bearer", "https://dify.example.com", "sk-plain");

        // when
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "system", null, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, environment,
                List.of(memory), List.of(vaultCredential));

        // then（格式化字段原样保留，列表不可变）
        assertEquals(environment, dto.environment());
        assertEquals(List.of(memory), dto.memoryStores());
        assertEquals(List.of(vaultCredential), dto.vaultCredentials());
    }

    @Test
    void should_holdModelTuning_when_construct_given_tuningFields() {
        // given（装配期解析的生效调优参数随契约出站）
        // when
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "system", null, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, null, null, null, "high", 200000);

        // then
        assertEquals("high", dto.modelEffort());
        assertEquals(200000, dto.modelContextWindow());
    }

    @Test
    void should_defaultTuningToEmpty_when_construct_given_legacyThirteenArgForm() {
        // given（兼容构造器：无调优语义的旧装配链）
        // when
        ResolvedAgentAssemblyDTO dto = sample();

        // then
        assertNull(dto.modelEffort());
        assertNull(dto.modelContextWindow());
    }

    @Test
    void should_reject_when_construct_given_nonPositiveContextWindow() {
        // given & when & then（上下文窗口档位必须为正）
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO("agent-a", 1, "v1", "system", null, "openai:gpt-4",
                        10, null, "https://api.example.com/v1", null, null, null, null, "high", 0));
    }

    @Test
    void should_holdToolPolicies_when_construct_given_policyList() {
        // given（tools_json 解析出的逐工具权限策略随契约出站）
        AgentToolPolicyDTO ask = new AgentToolPolicyDTO("Bash", AgentToolPolicyDTO.POLICY_ALWAYS_ASK);
        AgentToolPolicyDTO deny = new AgentToolPolicyDTO("WebFetch", AgentToolPolicyDTO.POLICY_ALWAYS_DENY);

        // when
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "system", null, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, null, null, null, null, null,
                List.of(ask, deny));

        // then（保持声明顺序，列表不可变）
        assertEquals(List.of(ask, deny), dto.toolPolicies());
        assertThrows(UnsupportedOperationException.class, () -> dto.toolPolicies().add(ask));
    }

    @Test
    void should_defaultToolPoliciesToEmpty_when_construct_given_nullOrLegacyForm() {
        // given & when（canonical 形态显式传 null → 空归一化）
        ResolvedAgentAssemblyDTO canonical = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "system", null, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, null, null, null, null, null, null);
        // when（15 参兼容构造器：无策略配置）
        ResolvedAgentAssemblyDTO legacy = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "system", null, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, null, null, null, "high", 200000);

        // then
        assertTrue(canonical.toolPolicies().isEmpty());
        assertTrue(legacy.toolPolicies().isEmpty());
    }

    @Test
    void should_defaultToolVisibilityToEmpty_when_construct_given_nullOrLegacyForm() {
        // given & when（canonical 17 参形态显式传 null → 空归一化）
        ResolvedAgentAssemblyDTO canonical = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "system", null, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, null, null, null, null, null, null, null);
        // when（16 参兼容构造器：无可见性约束的旧装配链）
        ResolvedAgentAssemblyDTO legacy = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "system", null, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, null, null, null, null, null,
                List.of(new AgentToolPolicyDTO("Bash", AgentToolPolicyDTO.POLICY_ALWAYS_ASK)));

        // then
        assertFalse(canonical.toolVisibility().hasConstraint());
        assertEquals(List.of(), canonical.toolVisibility().allowedTools());
        assertFalse(legacy.toolVisibility().hasConstraint());
    }

    @Test
    void should_holdToolVisibility_when_construct_given_visibilityDto() {
        // given（agent BC 聚合出的工具可见性随装配契约出站）
        AgentToolVisibilityDTO visibility =
                new AgentToolVisibilityDTO(List.of("Bash", "Read"), List.of("Write"));

        // when
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "system", null, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, null, null, null, null, null, null, visibility);

        // then（原样透传，不做二次加工）
        assertEquals(visibility, dto.toolVisibility());
        assertTrue(dto.toolVisibility().hasConstraint());
    }

    private ResolvedAgentAssemblyDTO sample() {
        return new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "你是数据分析专家", "# 项目约定\n\n统一使用中文回复。",
                "openai:gpt-4", 10, "sk-plain",
                "https://api.example.com/v1", null, null, null, null);
    }

    @Test
    void should_maskCredential_when_toString_given_plainTextCredential() {
        // given（含明文凭证的完整契约）
        ResolvedAgentAssemblyDTO dto = sample();

        // when
        String text = dto.toString();

        // then（明文凭证不得出现在 toString 中，脱敏后仍保留前缀可定位）
        assertFalse(text.contains("sk-plain"));
        assertTrue(text.contains("sk-p****"));
        // AGENTS.md 全文不随日志输出，仅暴露长度
        assertFalse(text.contains("统一使用中文回复"));
        assertTrue(text.contains("agentsMdLength="));
    }

    @Test
    void should_maskVaultToken_when_toString_given_plainTextVaultToken() {
        // given（装配契约内嵌保管库解密凭证明文）
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "system", null, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, null, null,
                List.of(new ResolvedVaultCredentialDTO(
                        "vault-1", "cr-1", "static_bearer", "https://dify.example.com", "sk-vault-secret")));

        // when
        String text = dto.toString();

        // then（保管库凭证明文不得随日志 / 异常链输出）
        assertFalse(text.contains("sk-vault-secret"));
        assertTrue(text.contains("token=****"));
    }
}
