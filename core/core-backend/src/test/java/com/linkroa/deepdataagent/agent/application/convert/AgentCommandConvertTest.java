package com.linkroa.deepdataagent.agent.application.convert;

import com.linkroa.deepdataagent.agent.application.command.CreateAgentCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishAgentVersionCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateAgentCommand;
import com.linkroa.deepdataagent.agent.application.query.ListAgentQuery;
import com.linkroa.deepdataagent.agent.controller.request.AgentConfigRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateAgentRequest;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Agent 配置请求转换器测试（模型引用 / 工具 / MCP / 技能 / 元数据结构化形态 → JSON 字符串）
 */
class AgentCommandConvertTest {

    /** 钉版技能版本键（创建时刻 epoch 微秒字符串）。 */
    private static final String EPOCH = "1759178010641129";

    @Test
    void should_mapAllFields_when_toCreateCommand_given_fullConfigRequest() {
        // given（字符串简写模型引用 + 结构化工具 / MCP / 技能 + 键值元数据）
        AgentConfigRequest request = new AgentConfigRequest(
                "客户台账", "台账 Agent", "你是台账助手", "ultimate",
                List.of(mcpToolset("github")),
                List.of(mcpServer("github")),
                List.of(skillBinding("skill_1")),
                null, metadata(), null);

        // when
        CreateAgentCommand command = AgentCommandConvert.INSTANCE.toCreateCommand(request);

        // then
        assertEquals("客户台账", command.name());
        assertEquals("台账 Agent", command.description());
        assertEquals("你是台账助手", command.systemPrompt());
        assertEquals("\"ultimate\"", command.modelJson());
        assertEquals("[{\"type\":\"mcp_toolset\",\"mcp_server_name\":\"github\"}]", command.toolsJson());
        assertEquals("[{\"name\":\"github\",\"type\":\"url\",\"url\":\"https://mcp.example.com\"}]",
                command.mcpServersJson());
        assertEquals("[{\"type\":\"custom\",\"skill_id\":\"skill_1\",\"version\":\"" + EPOCH + "\"}]",
                command.skillsJson());
        assertNull(command.multiagent());
        assertEquals("{\"team\":\"data\"}", command.metadataJson());
    }

    @Test
    void should_serializeObjectModel_when_toPublishCommand_given_objectModelForm() {
        // given（对象形态模型引用 {id, effort, context_window}，序列化后保留调优字段）
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", "ultimate");
        model.put("effort", "high");
        model.put("context_window", 200000);
        AgentConfigRequest request = new AgentConfigRequest(
                "客户台账 v2", null, null, model, null, null, null, null, null, null);

        // when
        PublishAgentVersionCommand command = AgentCommandConvert.INSTANCE.toPublishCommand("agent-a", request);

        // then
        assertEquals("agent-a", command.agentId());
        assertEquals("客户台账 v2", command.name());
        assertEquals("{\"id\":\"ultimate\",\"effort\":\"high\",\"context_window\":200000}", command.modelJson());
        assertNull(command.description());
        assertNull(command.systemPrompt());
        assertNull(command.toolsJson());
        assertNull(command.mcpServersJson());
        assertNull(command.skillsJson());
        assertNull(command.metadataJson());
    }

    @Test
    void should_rejectNumberModel_when_toCreateCommand_given_unsupportedModelForm() {
        // given（非法提交形态：数字既非字符串简写也非对象/数组）
        AgentConfigRequest request = new AgentConfigRequest(
                "客户台账", null, null, 42, null, null, null, null, null, null);

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandConvert.INSTANCE.toCreateCommand(request));
    }

    @Test
    void should_rejectNonEmptyAgentsMd_when_toCreateCommand_given_legacyField() {
        // given（agents_md 已废止：任何非空提交一律 400）
        AgentConfigRequest request = new AgentConfigRequest(
                "客户台账", null, null, "ultimate", null, null, null, null, null,
                "# 项目约定\n\n统一使用中文回复。");

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandConvert.INSTANCE.toCreateCommand(request));
    }

    @Test
    void should_buildListQuery_when_toListQuery_given_fullRawParams() {
        // given（HTTP 原始参数：keyword 带空白、metadata JSON 对象、RFC3339 时间界、游标）
        // when
        ListAgentQuery query = AgentCommandConvert.INSTANCE.toListQuery(
                "  台账  ", "archived", "{\"team\":\"data\"}",
                "2026-01-01T00:00:00+08:00", "2026-06-01T00:00:00+08:00",
                "50", "agent_after", null);

        // then
        assertEquals("台账", query.keyword());
        assertEquals("archived", query.status());
        assertEquals("{\"team\":\"data\"}", query.metadataJson());
        assertEquals(OffsetDateTime.parse("2026-01-01T00:00:00+08:00"), query.createdAtFrom());
        assertEquals(OffsetDateTime.parse("2026-06-01T00:00:00+08:00"), query.createdAtTo());
        assertEquals(50, query.cursor().limit());
        assertEquals("agent_after", query.cursor().afterId());
        assertNull(query.cursor().beforeId());
    }

    @Test
    void should_defaultCursorAndNullFilters_when_toListQuery_given_blankParams() {
        // given（全空白参数 → 归一为不过滤 + 缺省游标 limit 20）
        // when
        ListAgentQuery query = AgentCommandConvert.INSTANCE.toListQuery(
                "  ", null, " ", null, null, null, null, null);

        // then
        assertNull(query.keyword());
        assertNull(query.status());
        assertNull(query.metadataJson());
        assertNull(query.createdAtFrom());
        assertNull(query.createdAtTo());
        assertEquals(CursorPageParams.DEFAULT_LIMIT, query.cursor().limit());
    }

    @Test
    void should_rejectNonObjectMetadata_when_toListQuery_given_arrayMetadata() {
        // given（metadata 过滤条件须为 JSON 键值对象）
        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandConvert.INSTANCE.toListQuery(
                        null, null, "[1,2]", null, null, null, null, null));
    }

    @Test
    void should_rejectIllegalTimestamp_when_toListQuery_given_malformedCreatedAfter() {
        // given（非 RFC3339 时间界）
        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandConvert.INSTANCE.toListQuery(
                        null, null, null, "2026-01-01", null, null, null, null));
    }

    @Test
    void should_trimName_when_toUpdateCommand_given_request() {
        // given
        UpdateAgentRequest request = new UpdateAgentRequest("  新名称  ", "新描述", 3);

        // when
        UpdateAgentCommand command = AgentCommandConvert.INSTANCE.toUpdateCommand("agent-1", request);

        // then
        assertEquals("agent-1", command.agentId());
        assertEquals("新名称", command.name());
        assertEquals("新描述", command.description());
        assertEquals(3, command.version());
    }

    /** 工具配方条目（LinkedHashMap 保证键序稳定，便于 JSON 断言）。 */
    private static Map<String, Object> mcpToolset(String serverName) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "mcp_toolset");
        tool.put("mcp_server_name", serverName);
        return tool;
    }

    /** MCP 服务器声明条目。 */
    private static Map<String, Object> mcpServer(String name) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", name);
        server.put("type", "url");
        server.put("url", "https://mcp.example.com");
        return server;
    }

    /** 技能绑定条目（custom 钉版）。 */
    private static Map<String, Object> skillBinding(String skillId) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("type", "custom");
        binding.put("skill_id", skillId);
        binding.put("version", EPOCH);
        return binding;
    }

    /** 业务自定义元数据。 */
    private static Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("team", "data");
        return metadata;
    }
}