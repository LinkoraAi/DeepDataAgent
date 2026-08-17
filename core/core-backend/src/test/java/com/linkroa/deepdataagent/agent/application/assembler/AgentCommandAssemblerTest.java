package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.command.CreateAgentCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishAgentVersionCommand;
import com.linkroa.deepdataagent.agent.controller.request.AgentConfigRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Agent 配置请求装配器测试
 */
class AgentCommandAssemblerTest {

    private final AgentCommandAssembler assembler = new AgentCommandAssembler();

    @Test
    void should_mapAllFields_when_toCreateCommand_given_fullConfigRequest() {
        // given
        AgentConfigRequest request = new AgentConfigRequest(
                "客户台账", "台账 Agent", "你是台账助手", "mp-1",
                "[{\"skillId\":\"s1\",\"version\":1}]", "[]", "[\"ds-1\"]");

        // when
        CreateAgentCommand command = assembler.toCreateCommand(request);

        // then
        assertEquals("客户台账", command.name());
        assertEquals("台账 Agent", command.description());
        assertEquals("你是台账助手", command.system());
        assertEquals("mp-1", command.modelProfileId());
        assertEquals("[{\"skillId\":\"s1\",\"version\":1}]", command.skillIds());
        assertEquals("[]", command.knowledgeBaseIds());
        assertEquals("[\"ds-1\"]", command.dataSourceIds());
    }

    @Test
    void should_mapAgentIdAndPassThroughNulls_when_toPublishCommand_given_partialRequest() {
        // given
        AgentConfigRequest request = new AgentConfigRequest(
                "客户台账 v2", null, null, "mp-2", null, null, null);

        // when
        PublishAgentVersionCommand command = assembler.toPublishCommand("agent-a", request);

        // then
        assertEquals("agent-a", command.agentId());
        assertEquals("客户台账 v2", command.name());
        assertEquals("mp-2", command.modelProfileId());
        assertNull(command.description());
        assertNull(command.system());
        assertNull(command.skillIds());
    }
}