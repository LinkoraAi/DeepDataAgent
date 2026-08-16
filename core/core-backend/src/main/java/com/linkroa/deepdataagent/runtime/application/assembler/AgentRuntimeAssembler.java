package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import org.mapstruct.Mapper;

/**
 * Agent 运行时装配器。
 * <p>负责命令 → 领域对象的简单字段映射；复杂装配（AgentAssemblySpec）
 * 已下沉至 {@code RuntimeAgentAssemblyResolver}。</p>
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
}