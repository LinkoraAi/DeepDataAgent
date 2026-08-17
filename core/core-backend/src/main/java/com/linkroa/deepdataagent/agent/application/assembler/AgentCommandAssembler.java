package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.command.CreateAgentCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishAgentVersionCommand;
import com.linkroa.deepdataagent.agent.controller.request.AgentConfigRequest;
import org.springframework.stereotype.Component;

/**
 * Agent 配置请求装配器（Request → Command，创建与发布复用同一请求体「配置即版本」）
 */
@Component
public class AgentCommandAssembler {

    public CreateAgentCommand toCreateCommand(AgentConfigRequest request) {
        return new CreateAgentCommand(
                request.name(),
                request.description(),
                request.system(),
                request.modelProfileId(),
                request.skillIds(),
                request.knowledgeBaseIds(),
                request.dataSourceIds()
        );
    }

    public PublishAgentVersionCommand toPublishCommand(String agentId, AgentConfigRequest request) {
        return new PublishAgentVersionCommand(
                agentId,
                request.name(),
                request.description(),
                request.system(),
                request.modelProfileId(),
                request.skillIds(),
                request.knowledgeBaseIds(),
                request.dataSourceIds()
        );
    }
}