package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * Agent 运行时装配器。
 * <p>负责命令 → 领域对象的简单字段映射；复杂装配（AgentAssemblySpec）
 * 已下沉至 {@code RuntimeAgentAssemblyService}。</p>
 */
@Mapper
public interface AgentRuntimeConvert {

    AgentRuntimeConvert INSTANCE = Mappers.getMapper(AgentRuntimeConvert.class);

    /**
     * 命令 → 新会话领域模型（透传触发标记与全量挂载字段：环境 / 保管库 / 环境变量 / 挂载资源；
     * 触发标记为 null = 普通用户创建；关联记忆库由领域工厂从挂载资源派生）。
     */
    default AgentSession toSession(CreateSessionCommand command) {
        return AgentSession.createWithMounts(
                command.userId(),
                command.agentId(),
                command.agentVersion(),
                command.metadata(),
                command.title(),
                command.triggerType(),
                command.triggerId(),
                command.resources(),
                command.environmentId(),
                command.vaultIds(),
                command.environmentVariables()
        );
    }
}