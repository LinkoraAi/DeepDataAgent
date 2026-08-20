package com.linkroa.deepdataagent.runtime.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentAssemblySpec}（含 {@link AgentAssemblySpec.Sandbox}）不变量单测。
 */
class AgentAssemblySpecTest {

    @Test
    void should_buildSpec_when_construct_given_validInputs() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", 8L, 2L);

        // when
        AgentAssemblySpec spec = new AgentAssemblySpec(
                "agent-a", "数据分析Agent", "dashscope:qwen-plus", "你是助手",
                20, sandbox, "sk-cred", "https://api.example.com/v1", null, null);

        // then
        assertEquals("agent-a", spec.agentId());
        assertEquals("dashscope:qwen-plus", spec.model());
        assertEquals(20, spec.maxIters());
        assertEquals("ubuntu:22.04", spec.sandbox().image());
        assertEquals("sk-cred", spec.credential());
        assertEquals("https://api.example.com/v1", spec.apiEndpointUrl());
    }

    @Test
    void should_throw_when_construct_given_blankAgentId() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentAssemblySpec("", "数据分析Agent", "dashscope:qwen-plus",
                        "你是助手", 20, sandbox, null, null, null, null));
    }

    @Test
    void should_throw_when_construct_given_blankName() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentAssemblySpec("agent-a", " ", "dashscope:qwen-plus",
                        "你是助手", 20, sandbox, null, null, null, null));
    }

    @Test
    void should_throw_when_construct_given_nameTooLong() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentAssemblySpec("agent-a", "n".repeat(129), "dashscope:qwen-plus",
                        "你是助手", 20, sandbox, null, null, null, null));
    }

    @Test
    void should_throw_when_construct_given_blankModel() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentAssemblySpec("agent-a", "数据分析Agent", " ",
                        "你是助手", 20, sandbox, null, null, null, null));
    }

    @Test
    void should_throw_when_construct_given_nonPositiveMaxIters() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentAssemblySpec("agent-a", "数据分析Agent", "dashscope:qwen-plus",
                        "你是助手", 0, sandbox, null, null, null, null));
    }

    @Test
    void should_throw_when_construct_given_negativeSandboxMemory() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentAssemblySpec.Sandbox.of("ubuntu:22.04", -1L, 2L));
    }

    @Test
    void should_throw_when_construct_given_negativeSandboxCpu() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentAssemblySpec.Sandbox.of("ubuntu:22.04", 8L, 0L));
    }

    @Test
    void should_throw_when_construct_given_blankSandboxImage() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentAssemblySpec.Sandbox.of(" ", 8L, 2L));
    }

    @Test
    void should_maskCredential_when_toString_given_plainCredential() {
        // given（明文凭证不得随 toString 泄露）
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);
        AgentAssemblySpec spec = new AgentAssemblySpec(
                "agent-a", "数据分析Agent", "dashscope:qwen-plus", "你是助手",
                20, sandbox, "sk-plain-secret", "https://api.example.com/v1", null, null);

        // when
        String text = spec.toString();

        // then
        assertFalse(text.contains("sk-plain-secret"));
        assertTrue(text.contains("sk-p****"));
    }
}