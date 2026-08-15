package com.linkroa.deepdataagent.runtime.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentAssemblySpec}（含 {@link AgentAssemblySpec.Sandbox}）不变量单测。
 */
class AgentAssemblySpecTest {

    @Test
    void should_buildSpec_when_of_given_validInputs() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", 8L, 2L);

        // when
        AgentAssemblySpec spec = AgentAssemblySpec.of(
                "agent-a", "数据分析Agent", "描述", "dashscope:qwen-plus", "你是助手",
                List.of("query_datasource", "execute_sql"), 20, sandbox);

        // then
        assertEquals("agent-a", spec.agentId());
        assertEquals("dashscope:qwen-plus", spec.model());
        assertEquals(20, spec.maxIters());
        assertEquals("ubuntu:22.04", spec.sandbox().image());
        assertEquals(2, spec.toolNames().size());
    }

    @Test
    void should_defaultEmptyTools_when_of_given_nullToolNames() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);

        // when
        AgentAssemblySpec spec = AgentAssemblySpec.of(
                "agent-a", "数据分析Agent", "描述", "dashscope:qwen-plus", "你是助手",
                null, 20, sandbox);

        // then
        assertEquals(List.of(), spec.toolNames());
    }

    @Test
    void should_copyTools_when_of_given_mutableToolNames() {
        // given
        List<String> mutable = new java.util.ArrayList<>(List.of("tool-a"));
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);

        // when
        AgentAssemblySpec spec = AgentAssemblySpec.of(
                "agent-a", "数据分析Agent", "描述", "dashscope:qwen-plus", "你是助手",
                mutable, 20, sandbox);
        mutable.add("tool-b");

        // then
        assertEquals(List.of("tool-a"), spec.toolNames());
        assertNotSame(mutable, spec.toolNames());
    }

    @Test
    void should_throw_when_construct_given_blankAgentId() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentAssemblySpec.of("", "数据分析Agent", "描述", "dashscope:qwen-plus",
                        "你是助手", List.of(), 20, sandbox));
    }

    @Test
    void should_throw_when_construct_given_blankName() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentAssemblySpec.of("agent-a", " ", "描述", "dashscope:qwen-plus",
                        "你是助手", List.of(), 20, sandbox));
    }

    @Test
    void should_throw_when_construct_given_nameTooLong() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentAssemblySpec.of("agent-a", "n".repeat(129), "描述", "dashscope:qwen-plus",
                        "你是助手", List.of(), 20, sandbox));
    }

    @Test
    void should_throw_when_construct_given_blankModel() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentAssemblySpec.of("agent-a", "数据分析Agent", "描述", " ",
                        "你是助手", List.of(), 20, sandbox));
    }

    @Test
    void should_throw_when_construct_given_nonPositiveMaxIters() {
        // given
        AgentAssemblySpec.Sandbox sandbox = AgentAssemblySpec.Sandbox.of("ubuntu:22.04", null, null);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentAssemblySpec.of("agent-a", "数据分析Agent", "描述", "dashscope:qwen-plus",
                        "你是助手", List.of(), 0, sandbox));
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
}