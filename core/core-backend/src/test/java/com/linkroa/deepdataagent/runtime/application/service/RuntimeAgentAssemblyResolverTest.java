package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.agent.application.contract.EnvironmentReferenceDTO;
import com.linkroa.deepdataagent.agent.application.contract.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.application.port.AgentVersionAssemblyPort;
import com.linkroa.deepdataagent.memory.application.contract.MemoryStoreReferenceDTO;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.MemoryStoreRef;
import com.linkroa.deepdataagent.runtime.infrastructure.client.SkillPackageMaterializer;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RuntimeAgentAssemblyResolver} 运行时装配单测。
 * <p>验证装配完全来自 Agent 台账（无全局回退）：model / maxIters / system 取解析结果
 * 而非 {@code app.agent} 全局配置；沙箱等运行时基础设施参数取配置；
 * 模型凭证 / API 端点一并承载于装配规格。</p>
 */
@ExtendWith(MockitoExtension.class)
class RuntimeAgentAssemblyResolverTest {

    @Mock private AgentVersionAssemblyPort agentVersionAssemblyPort;

    private AgentRuntimeProperties properties;
    private RuntimeAgentAssemblyResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new AgentRuntimeProperties();
        properties.setSandboxImage("python:3.12");
        properties.setSandboxMemoryBytes(8192L);
        properties.setSandboxCpuCount(4L);
        resolver = new RuntimeAgentAssemblyResolver();
        ReflectionTestUtils.setField(resolver, "agentVersionAssemblyPort", agentVersionAssemblyPort);
        ReflectionTestUtils.setField(resolver, "skillPackageMaterializer", new SkillPackageMaterializer());
        ReflectionTestUtils.setField(resolver, "properties", properties);
    }

    @Test
    void should_assembleSpec_when_assemble_given_session() {
        // given（模型/系统提示词/迭代上限仅来自台账，全局装配配置已移除不存在回退）
        when(agentVersionAssemblyPort.resolve("agent-a", "1"))
                .thenReturn(new ResolvedAgentAssemblyDTO(
                        "agent-a", 1, "v1", "台账系统提示词",
                        "openai:gpt-4", 10, "sk-plain",
                        "https://api.example.com/v1", null, null, null, null));
        AgentSession session = AgentSession.create("u-1", "agent-a", "1", "{}", null);

        // when
        AgentAssemblySpec spec = resolver.assemble(session);

        // then（model/system/maxIters 完全来自解析台账）
        assertEquals("agent-a", spec.agentId());
        assertEquals("v1", spec.name());
        assertEquals("openai:gpt-4", spec.model());
        assertEquals("台账系统提示词", spec.systemPrompt());
        assertEquals(10, spec.maxIters());
        // 沙箱等运行时基础设施参数仍取 app.agent 配置
        assertEquals("python:3.12", spec.sandbox().image());
        assertEquals(8192L, spec.sandbox().memoryBytes());
        assertEquals(4L, spec.sandbox().cpuCount());
        // 凭证/API 端点一并承载于装配规格
        assertEquals("sk-plain", spec.credential());
        assertEquals("https://api.example.com/v1", spec.apiEndpointUrl());
        assertTrue(spec.skills().isEmpty());
        assertTrue(spec.memoryStoreRefs().isEmpty());
    }

    @Test
    void should_useEnvironmentAndMemory_when_assemble_given_versionWithReferences() {
        // given（版本引用环境与记忆库：沙箱规格取环境、记忆引用装配进规格）
        EnvironmentReferenceDTO environment = new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", "LOCAL", "python:3.12", 2048, 2.0, "container", 300);
        when(agentVersionAssemblyPort.resolve("agent-a", "1"))
                .thenReturn(new ResolvedAgentAssemblyDTO(
                        "agent-a", 1, "v1", "台账系统提示词",
                        "openai:gpt-4", 10, "sk-plain",
                        "https://api.example.com/v1", null, null, environment,
                        List.of(new MemoryStoreReferenceDTO("mem-1", "会话记忆", "SHORT_TERM", null))));
        AgentSession session = AgentSession.create("u-1", "agent-a", "1", "{}", null);

        // when
        AgentAssemblySpec spec = resolver.assemble(session);

        // then（环境规格单位换算为运行时字节/整核，记忆引用物化为运行时值对象）
        assertEquals("python:3.12", spec.sandbox().image());
        assertEquals(2048L * 1024L * 1024L, spec.sandbox().memoryBytes());
        assertEquals(2L, spec.sandbox().cpuCount());
        assertEquals(1, spec.memoryStoreRefs().size());
        MemoryStoreRef ref = spec.memoryStoreRefs().get(0);
        assertEquals("mem-1", ref.memoryStoreId());
        assertEquals("SHORT_TERM", ref.type());
    }

    @Test
    void should_delegateValidation_when_assertResolvable_given_agentIdAndVersion() {
        // given（轻量校验：会话创建前置校验链，仅委托端口，不触发装配解析与凭证解密）

        // when
        resolver.assertResolvable("agent-a", "2");

        // then（校验失败向上传播 404，端口被正确委托）
        verify(agentVersionAssemblyPort).assertResolvable("agent-a", "2");
    }

    @Test
    void should_delegateActiveVersion_when_activeVersionNumber_given_agentId() {
        // given
        when(agentVersionAssemblyPort.activeVersionNumber("agent-a")).thenReturn("2");

        // when
        String version = resolver.activeVersionNumber("agent-a");

        // then
        assertEquals("2", version);
        verify(agentVersionAssemblyPort).activeVersionNumber("agent-a");
    }
}