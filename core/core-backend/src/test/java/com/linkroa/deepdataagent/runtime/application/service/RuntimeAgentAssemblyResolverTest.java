package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.agent.application.contract.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.application.port.AgentVersionAssemblyPort;
import com.linkroa.deepdataagent.runtime.application.assembler.AgentAssemblyAssemblerImpl;
import com.linkroa.deepdataagent.runtime.domain.gateway.AgentToolGateway;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RuntimeAgentAssemblyResolver} 运行时装配单测。
 * <p>验证装配完全来自 Agent 台账（无全局回退）：model / maxIters / system 取解析结果
 * 而非 {@code app.agent} 全局配置；工具集排序注入；沙箱等运行时基础设施参数取配置；
 * 模型凭证 / API 端点经 {@code ModelAccess} 透传进工厂装配（不进组装规格）。</p>
 */
@ExtendWith(MockitoExtension.class)
class RuntimeAgentAssemblyResolverTest {

    @Mock private AgentVersionAssemblyPort agentVersionAssemblyPort;
    @Mock private AgentToolGateway toolGateway;

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
        ReflectionTestUtils.setField(resolver, "agentAssemblyAssembler", new AgentAssemblyAssemblerImpl());
        ReflectionTestUtils.setField(resolver, "properties", properties);
        ReflectionTestUtils.setField(resolver, "toolGateway", toolGateway);
    }

    @Test
    void should_assembleSpecAndModelAccess_when_assemble_given_session() {
        // given（模型/系统提示词/迭代上限仅来自台账，全局装配配置已移除不存在回退）
        when(agentVersionAssemblyPort.resolve("agent-a", "1"))
                .thenReturn(new ResolvedAgentAssemblyDTO(
                        "agent-a", 1, "v1", "台账系统提示词",
                        "openai:gpt-4", 10, "sk-plain",
                        "https://api.example.com/v1"));
        when(toolGateway.availableToolNames()).thenReturn(Set.of("echo", "calculator", "current_time"));
        AgentSession session = AgentSession.create("u-1", "agent-a", "1", "{}", null);

        // when
        RuntimeAgentAssemblyResolver.AssembledAssembly result = resolver.assemble(session);

        // then（model/system/maxIters 完全来自解析台账）
        AgentAssemblySpec spec = result.spec();
        assertEquals("agent-a", spec.agentId());
        assertEquals("v1", spec.name());
        assertEquals("openai:gpt-4", spec.model());
        assertEquals("台账系统提示词", spec.systemPrompt());
        assertEquals(10, spec.maxIters());
        // 工具名排序注入，保证装配确定性
        assertEquals(List.of("calculator", "current_time", "echo"), spec.toolNames());
        // 沙箱等运行时基础设施参数仍取 app.agent 配置
        assertEquals("python:3.12", spec.sandbox().image());
        assertEquals(8192L, spec.sandbox().memoryBytes());
        assertEquals(4L, spec.sandbox().cpuCount());
        // 模型凭证 / API 端点经 ModelAccess 透传（不进组装规格，仅入工厂装配）
        assertEquals("sk-plain", result.modelAccess().apiKey());
        assertEquals("https://api.example.com/v1", result.modelAccess().baseUrl());
    }

    @Test
    void should_delegateValidation_when_assertResolvable_given_agentIdAndVersion() {
        // given（轻量校验：会话创建前置校验链，仅委托端口，不触发装配解析与凭证解密）

        // when
        resolver.assertResolvable("agent-a", "2");

        // then（校验失败向上传播 404，端口被正确委托）
        verify(agentVersionAssemblyPort).assertResolvable("agent-a", "2");
    }
}