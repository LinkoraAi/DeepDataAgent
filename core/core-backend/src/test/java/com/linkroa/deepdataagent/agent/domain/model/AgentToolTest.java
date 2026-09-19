package com.linkroa.deepdataagent.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentTool} 单元测试：四类工具配置解析、互斥/重名/schema 校验与序列化回显。
 */
class AgentToolTest {

    @Test
    void should_parseAgentToolset_when_parse_given_whitelistAndConfigs() {
        // given
        String json = "[{\"type\":\"agent_toolset_20260401\",\"enabled_tools\":[\"Read\",\"Bash\"],"
                + "\"disallowed_tools\":[\"Write\"],"
                + "\"configs\":[{\"name\":\"Bash\",\"enabled\":true,\"permission_policy\":\"always_ask\"}]}]";

        // when
        List<AgentTool> tools = AgentTool.parse(json);

        // then
        assertEquals(1, tools.size());
        AgentTool tool = tools.get(0);
        assertEquals(AgentTool.TYPE_AGENT_TOOLSET, tool.type());
        assertEquals(List.of("Read", "Bash"), tool.enabledTools());
        assertEquals(List.of("Write"), tool.disallowedTools());
        assertEquals(1, tool.configs().size());
        assertEquals("Bash", tool.configs().get(0).name());
        assertEquals(AgentTool.ToolConfig.POLICY_ALWAYS_ASK, tool.configs().get(0).permissionPolicy());
    }

    @Test
    void should_throw_when_parse_given_browserToolset() {
        // given & when（浏览器工具集本期不实现，解析即 400 拒绝）
        // then
        assertThrows(IllegalArgumentException.class,
                () -> AgentTool.parse("[{\"type\":\"browser_toolset_20260714\"}]"));
    }

    @Test
    void should_parseCustomTool_when_parse_given_objectSchema() {
        // given
        String json = "[{\"type\":\"custom\",\"name\":\"query_db\",\"description\":\"查询数据库\","
                + "\"input_schema\":{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}}}}]";

        // when
        List<AgentTool> tools = AgentTool.parse(json);

        // then
        AgentTool tool = tools.get(0);
        assertEquals("query_db", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    void should_returnEmpty_when_parse_given_blankJson() {
        // given & when & then
        assertEquals(List.of(), AgentTool.parse(null));
        assertEquals(List.of(), AgentTool.parse(" "));
    }

    @Test
    void should_throw_when_constructor_given_illegalType() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentTool("unknown", List.of(), List.of(), List.of(), null, null, null, Map.of()));
    }

    @Test
    void should_throw_when_constructor_given_overlapEnabledAndDisallowed() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentTool(AgentTool.TYPE_AGENT_TOOLSET, List.of("Read"), List.of("Read"),
                        List.of(), null, null, null, Map.of()));
    }

    @Test
    void should_throw_when_constructor_given_mcpToolsetWithoutServerName() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentTool(AgentTool.TYPE_MCP_TOOLSET, List.of(), List.of(), List.of(),
                        " ", null, null, Map.of()));
    }

    @Test
    void should_throw_when_constructor_given_customNameCollidingBuiltin() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> customTool("Read", Map.of("type", "object")));
    }

    @Test
    void should_throw_when_constructor_given_customNameWithMcpPrefix() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> customTool("mcp__internal", Map.of("type", "object")));
    }

    @Test
    void should_throw_when_constructor_given_customSchemaTypeNotObject() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> customTool("query_db", Map.of("type", "string")));
    }

    @Test
    void should_throw_when_constructor_given_customWithConfigs() {
        // given
        AgentTool.ToolConfig config = new AgentTool.ToolConfig("query_db", true, null);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentTool(AgentTool.TYPE_CUSTOM, List.of(), List.of(), List.of(config),
                        null, "query_db", "查询", Map.of("type", "object")));
    }

    @Test
    void should_throw_when_constructor_given_illegalPermissionPolicy() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentTool.ToolConfig("Bash", true, "auto_allow"));
    }

    @Test
    void should_roundTrip_when_toJson_given_parsedTools() {
        // given
        String json = "[{\"type\":\"mcp_toolset\",\"mcp_server_name\":\"github\","
                + "\"configs\":[{\"name\":\"create_issue\",\"permission_policy\":\"always_ask\"}]}]";

        // when
        String serialized = AgentTool.toJson(AgentTool.parse(json));

        // then
        assertTrue(serialized.contains("\"mcp_server_name\":\"github\""));
        assertTrue(serialized.contains("\"permission_policy\":\"always_ask\""));
        assertEquals(List.of(AgentTool.parse(json).get(0)), AgentTool.parse(serialized));
        assertNull(AgentTool.toJson(List.of()));
    }

    private static AgentTool customTool(String name, Map<String, Object> schema) {
        return new AgentTool(AgentTool.TYPE_CUSTOM, List.of(), List.of(), List.of(), null,
                name, "描述", schema);
    }
}
