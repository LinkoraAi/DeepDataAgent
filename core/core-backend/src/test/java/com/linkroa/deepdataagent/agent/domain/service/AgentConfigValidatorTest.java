package com.linkroa.deepdataagent.agent.domain.service;

import com.linkroa.deepdataagent.agent.domain.model.AgentTool;
import com.linkroa.deepdataagent.agent.domain.model.McpServer;
import com.linkroa.deepdataagent.agent.domain.model.SkillBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentConfigValidator} 单元测试：数量上限与 mcp_toolset 引用完整性。
 */
class AgentConfigValidatorTest {

    /** 钉版版本键（创建时刻 epoch 微秒字符串）。 */
    private static final String EPOCH = "1759178010641129";

    @Test
    void should_pass_when_validate_given_consistentConfiguration() {
        // given
        List<McpServer> servers = List.of(new McpServer("github", McpServer.TYPE_URL, "https://mcp.example.com"));
        List<AgentTool> tools = List.of(mcpToolset("github"));
        List<SkillBinding> skills = List.of(new SkillBinding(SkillBinding.TYPE_CUSTOM, "skill_1", EPOCH));

        // when & then
        assertDoesNotThrow(() -> AgentConfigValidator.validate(tools, servers, skills));
    }

    @Test
    void should_throw_when_validate_given_mcpToolsetReferencingUnknownServer() {
        // given
        List<McpServer> servers = List.of(new McpServer("github", McpServer.TYPE_URL, "https://mcp.example.com"));
        List<AgentTool> tools = List.of(mcpToolset("gitlab"));

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfigValidator.validate(tools, servers, List.of()));
    }

    @Test
    void should_throw_when_validate_given_moreThanMaxTools() {
        // given
        List<AgentTool> tools = IntStream.rangeClosed(1, AgentTool.MAX_TOOLS + 1)
                .mapToObj(i -> new AgentTool(AgentTool.TYPE_AGENT_TOOLSET, List.of(), List.of(),
                        List.of(), null, null, null, Map.of()))
                .toList();

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfigValidator.validate(tools, List.of(), List.of()));
    }

    @Test
    void should_throw_when_validate_given_moreThanMaxSkills() {
        // given
        List<SkillBinding> skills = IntStream.rangeClosed(1, SkillBinding.MAX_BINDINGS + 1)
                .mapToObj(i -> new SkillBinding(SkillBinding.TYPE_CUSTOM, "skill_" + i, EPOCH))
                .toList();

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfigValidator.validate(List.of(), List.of(), skills));
    }

    @Test
    void should_throw_when_validate_given_duplicateServerNames() {
        // given（版本内同名服务器：实名歧义 + 装配期连接互相覆盖）
        List<McpServer> servers = List.of(
                new McpServer("github", McpServer.TYPE_URL, "https://a.example.com"),
                new McpServer("github", McpServer.TYPE_URL, "https://b.example.com"));

        // when & then（无工具配置时同样拦截）
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfigValidator.validate(List.of(), servers, List.of()));
    }

    @Test
    void should_pass_when_validate_given_nullLists() {
        // given & when & then
        assertDoesNotThrow(() -> AgentConfigValidator.validate(null, null, null));
    }

    private static AgentTool mcpToolset(String serverName) {
        return new AgentTool(AgentTool.TYPE_MCP_TOOLSET, List.of(), List.of(), List.of(),
                serverName, null, null, Map.of());
    }
}
