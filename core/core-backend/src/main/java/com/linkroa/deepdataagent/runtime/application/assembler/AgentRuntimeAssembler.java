package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import org.mapstruct.Mapper;

import java.util.Set;

/**
 * Agent 运行时装配器。
 * <p>负责命令/领域对象的组装：{@link CreateSessionCommand} → {@link AgentSession}（领域工厂），
 * 会话 + 运行时配置 → {@link AgentAssemblySpec}（工厂装配规格，保持框架无关注）。</p>
 */
@Mapper(componentModel = "spring")
public interface AgentRuntimeAssembler {

    /**
     * 命令 → 新会话领域模型（IDLE 初始态）。
     */
    default AgentSession toSession(CreateSessionCommand command) {
        return AgentSession.create(
                command.userId(),
                command.agentId(),
                command.agentVersion(),
                command.metadata(),
                command.title()
        );
    }

    /**
     * 会话 + 运行时配置 + 可用工具集 → Agent 装配规格。
     * <p>按全局运行时配置装配（模型 / 系统提示词 / 沙箱 / 迭代上限 / 工具集），
     * 工具集取自 {@code AgentToolGateway.availableToolNames()}，未来可扩展按 agentId 差异化配置。</p>
     */
    default AgentAssemblySpec toSpec(AgentSession session, AgentRuntimeProperties properties, Set<String> toolNames) {
        return AgentAssemblySpec.of(
                session.agentId(),
                "agent-" + session.agentId(),
                "DeepDataAgent 运行时装配的 Agent（版本 " + session.agentVersion() + "）",
                properties.getModelId(),
                properties.getSystemPrompt(),
                toolNames.stream().sorted().toList(),
                properties.getMaxIters(),
                AgentAssemblySpec.Sandbox.of(
                        properties.getSandboxImage(),
                        properties.getSandboxMemoryBytes(),
                        properties.getSandboxCpuCount()
                )
        );
    }
}