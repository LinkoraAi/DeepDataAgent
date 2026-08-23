package com.linkroa.deepdataagent.agent.application.convert;

import com.linkroa.deepdataagent.agent.application.command.CreateAgentCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishAgentVersionCommand;
import com.linkroa.deepdataagent.agent.controller.request.AgentConfigRequest;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * Agent 配置请求转换器（Request → Command，创建与发布复用同一请求体「配置即版本」）。
 */
@Mapper
public interface AgentCommandConvert {

    AgentCommandConvert INSTANCE = Mappers.getMapper(AgentCommandConvert.class);

    default CreateAgentCommand toCreateCommand(AgentConfigRequest request) {
        return new CreateAgentCommand(
                request.name(),
                request.description(),
                request.system(),
                request.modelProfileId(),
                request.skillIds(),
                request.knowledgeBaseIds(),
                request.dataSourceIds(),
                request.environmentId(),
                request.memoryStoreIds()
        );
    }

    default PublishAgentVersionCommand toPublishCommand(String agentId, AgentConfigRequest request) {
        return new PublishAgentVersionCommand(
                agentId,
                request.name(),
                request.description(),
                request.system(),
                request.modelProfileId(),
                request.skillIds(),
                request.knowledgeBaseIds(),
                request.dataSourceIds(),
                request.environmentId(),
                request.memoryStoreIds()
        );
    }
}