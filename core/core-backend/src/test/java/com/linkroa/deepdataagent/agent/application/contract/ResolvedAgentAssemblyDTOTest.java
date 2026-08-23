package com.linkroa.deepdataagent.agent.application.contract;

import com.linkroa.deepdataagent.memory.application.contract.MemoryStoreReferenceDTO;
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
 * 以及 environment / memory_store 引用字段的可空 / 空归一化。</p>
 */
class ResolvedAgentAssemblyDTOTest {

    private static final int MAX_SYSTEM_LENGTH = 20000;

    @Test
    void should_acceptValidContract_when_construct_given_allRequiredFields() {
        // given
        // when
        ResolvedAgentAssemblyDTO dto = sample();

        // then
        assertEquals("agent-a", dto.agentId());
        assertEquals(1, dto.versionNumber());
        assertEquals("v1", dto.versionName());
        assertEquals("你是数据分析专家", dto.system());
        assertEquals("openai:gpt-4", dto.modelIndicator());
        assertEquals(10, dto.maxIters());
        assertEquals("sk-plain", dto.credential());
        assertEquals("https://api.example.com/v1", dto.apiEndpointUrl());
        assertTrue(dto.skills().isEmpty());
        assertTrue(dto.dataSourceIds().isEmpty());
        assertNull(dto.environment());
        assertTrue(dto.memoryStores().isEmpty());
    }

    @Test
    void should_acceptMaxLengthSystem_when_construct_given_20000CharSystem() {
        // given（契约允许 20000 字符上限的系统提示词）
        String system = "s".repeat(MAX_SYSTEM_LENGTH);

        // when & then
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", system, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, null, null, null);
        assertEquals(MAX_SYSTEM_LENGTH, dto.system().length());
    }

    @Test
    void should_reject_when_construct_given_blankAgentId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO(" ", 1, "v1", "system", "openai:gpt-4",
                        10, null, "https://api.example.com/v1", null, null, null, null));
    }

    @Test
    void should_reject_when_construct_given_zeroVersionNumber() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO("agent-a", 0, "v1", "system", "openai:gpt-4",
                        10, null, "https://api.example.com/v1", null, null, null, null));
    }

    @Test
    void should_reject_when_construct_given_blankVersionName() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO("agent-a", 1, "", "system", "openai:gpt-4",
                        10, null, "https://api.example.com/v1", null, null, null, null));
    }

    @Test
    void should_reject_when_construct_given_blankModelIndicator() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO("agent-a", 1, "v1", "system", "",
                        10, null, "https://api.example.com/v1", null, null, null, null));
    }

    @Test
    void should_reject_when_construct_given_zeroMaxIters() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO("agent-a", 1, "v1", "system", "openai:gpt-4",
                        0, null, "https://api.example.com/v1", null, null, null, null));
    }

    @Test
    void should_reject_when_construct_given_oversizedSystem() {
        // given（超出契约上限 20000 字符）
        String system = "s".repeat(MAX_SYSTEM_LENGTH + 1);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedAgentAssemblyDTO("agent-a", 1, "v1", system, "openai:gpt-4",
                        10, null, "https://api.example.com/v1", null, null, null, null));
    }

    @Test
    void should_normalizeNullReferences_when_construct_given_nullEnvironmentAndMemory() {
        // given（环境可空、记忆库空归一化为不可变空列表）
        // when
        ResolvedAgentAssemblyDTO dto = sample();

        // then
        assertNull(dto.environment());
        assertTrue(dto.memoryStores().isEmpty());
    }

    @Test
    void should_keepReferences_when_construct_given_environmentAndMemory() {
        // given
        EnvironmentReferenceDTO environment = new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", "LOCAL", "python:3.12", 2048, 2.0, "container", 300);
        MemoryStoreReferenceDTO memory = new MemoryStoreReferenceDTO("mem-1", "会话记忆", "SHORT_TERM", null);

        // when
        ResolvedAgentAssemblyDTO dto = new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "system", "openai:gpt-4", 10, null,
                "https://api.example.com/v1", null, null, environment, List.of(memory));

        // then（格式化字段原样保留，列表不可变）
        assertEquals(environment, dto.environment());
        assertEquals(List.of(memory), dto.memoryStores());
    }

    private ResolvedAgentAssemblyDTO sample() {
        return new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "你是数据分析专家",
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
    }
}