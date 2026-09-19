package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.ModelEffort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentVersionTest {

    /** 钉版版本键（创建时刻 epoch 微秒字符串）。 */
    private static final String EPOCH = "1759178010641129";
    /** 模型引用字符串简写形态。 */
    private static final String MODEL_SHORTHAND = "\"ultimate\"";

    @Test
    void should_createVersion_when_create_given_validFields() {
        // given // when
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", "初始版本", "你是销售助手",
                null, MODEL_SHORTHAND, null, null, null, null, null);

        // then
        assertEquals(1, version.versionNumber());
        assertEquals("你是销售助手", version.systemPrompt());
        assertEquals("初始版本", version.description());
        assertEquals("v-1", version.versionId());
    }

    @Test
    void should_throwException_when_create_given_zeroVersionNumber() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> AgentVersion.create("v-1", "agent-1", 0, "v0", null,
                        "sysPrompt", null, MODEL_SHORTHAND, null, null, null, null, null));
    }

    @Test
    void should_throwException_when_create_given_blankModelReference() {
        // given（内部 profileId 与对外模型引用同时缺失 → 无模型可装配）
        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> AgentVersion.create("v-1", "agent-1", 1, "v1", null,
                        "sysPrompt", "", null, null, null, null, null, null));
    }

    @Test
    void should_defaultSystemPromptToEmpty_when_create_given_nullSystemPrompt() {
        // given // when
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, null, null, MODEL_SHORTHAND,
                null, null, null, null, null);

        // then
        assertEquals("", version.systemPrompt());
    }

    @Test
    void should_acceptMaxLengthSystemPrompt_when_create_given_100000CharSystemPrompt() {
        // given（系统提示词上限 100000 字符）
        String systemPrompt = "s".repeat(AgentVersion.MAX_SYS_PROMPT_LENGTH);

        // when
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, systemPrompt, null, MODEL_SHORTHAND,
                null, null, null, null, null);

        // then
        assertEquals(AgentVersion.MAX_SYS_PROMPT_LENGTH, version.systemPrompt().length());
    }

    @Test
    void should_throwException_when_create_given_oversizedSystemPrompt() {
        // given（超出 100000 字符上限）
        String systemPrompt = "s".repeat(AgentVersion.MAX_SYS_PROMPT_LENGTH + 1);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> AgentVersion.create("v-1", "agent-1", 1, "v1", null, systemPrompt, null,
                        MODEL_SHORTHAND, null, null, null, null, null));
    }

    @Test
    void should_acceptName_when_create_given_nameAt256Boundary() {
        // given（版本名称上限 256 字符）
        String name = "a".repeat(AgentVersion.MAX_NAME_LENGTH);

        // when
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, name, null, "sysPrompt", null, MODEL_SHORTHAND,
                null, null, null, null, null);

        // then
        assertEquals(AgentVersion.MAX_NAME_LENGTH, version.name().length());
    }

    @Test
    void should_throwException_when_create_given_nameExceeds256Chars() {
        // given（超出 256 字符上限）
        String name = "a".repeat(AgentVersion.MAX_NAME_LENGTH + 1);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> AgentVersion.create("v-1", "agent-1", 1, name, null, "sysPrompt", null,
                        MODEL_SHORTHAND, null, null, null, null, null));
    }

    @Test
    void should_acceptDescription_when_create_given_descriptionAt2048Boundary() {
        // given（版本描述上限 2048 字符）
        String description = "d".repeat(AgentVersion.MAX_DESCRIPTION_LENGTH);

        // when
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", description, "sysPrompt", null, MODEL_SHORTHAND,
                null, null, null, null, null);

        // then
        assertEquals(AgentVersion.MAX_DESCRIPTION_LENGTH, version.description().length());
    }

    @Test
    void should_throwException_when_create_given_descriptionExceeds2048Chars() {
        // given（超出 2048 字符上限）
        String description = "d".repeat(AgentVersion.MAX_DESCRIPTION_LENGTH + 1);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> AgentVersion.create("v-1", "agent-1", 1, "v1", description, "sysPrompt", null,
                        MODEL_SHORTHAND, null, null, null, null, null));
    }

    @Test
    void should_returnSkills_when_parseSkills_given_jsonSkills() {
        // given（custom 钉版 + catalog 动态版）
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, "sysPrompt", null, MODEL_SHORTHAND,
                null, null,
                "[{\"type\":\"custom\",\"skill_id\":\"skill_1\",\"version\":\"1759178010641129\"},"
                        + "{\"type\":\"catalog\",\"skill_id\":\"skill_2\"}]",
                null, null);

        // when
        var bindings = version.parseSkills();

        // then
        assertEquals(2, bindings.size());
        assertEquals("custom", bindings.get(0).type());
        assertEquals("skill_1", bindings.get(0).skillId());
        assertEquals(EPOCH, bindings.get(0).version());
        assertTrue(bindings.get(0).isCustom());
        assertEquals("catalog", bindings.get(1).type());
        assertEquals("skill_2", bindings.get(1).skillId());
        assertNull(bindings.get(1).version());
        assertFalse(bindings.get(1).isCustom());
    }

    @Test
    void should_returnEmptySkills_when_parseSkills_given_blankSkillsJson() {
        // given
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, "sysPrompt", null, MODEL_SHORTHAND,
                null, null, null, null, null);

        // when
        var bindings = version.parseSkills();

        // then
        assertTrue(bindings.isEmpty());
    }

    @Test
    void should_parseModel_when_parseModel_given_shorthandModelJson() {
        // given
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, "sysPrompt", null, MODEL_SHORTHAND,
                null, null, null, null, null);

        // when
        ModelRef model = version.parseModel();

        // then
        assertEquals("ultimate", model.id());
        assertTrue(model.shorthand());
    }

    @Test
    void should_returnNullModel_when_parseModel_given_blankModelJson() {
        // given（内部 profileId 已在发布期固化，对外模型引用缺省）
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, "sysPrompt", "profile-1", null,
                null, null, null, null, null);

        // when // then
        assertNull(version.parseModel());
    }

    @Test
    void should_parseToolsAndMcpServers_when_parse_given_structuredJson() {
        // given
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, "sysPrompt", null,
                "{\"id\":\"ultimate\",\"effort\":\"high\"}",
                "[{\"type\":\"mcp_toolset\",\"mcp_server_name\":\"github\"}]",
                "[{\"name\":\"github\",\"type\":\"url\",\"url\":\"https://mcp.example.com\"}]",
                null, null, null);

        // when
        ModelRef model = version.parseModel();
        List<AgentTool> tools = version.parseTools();
        List<McpServer> servers = version.parseMcpServers();

        // then
        assertEquals(ModelEffort.HIGH, model.effort());
        assertEquals(AgentTool.TYPE_MCP_TOOLSET, tools.get(0).type());
        assertEquals("github", tools.get(0).mcpServerName());
        assertEquals("github", servers.get(0).name());
    }

    @Test
    void should_parseMetadata_when_parseMetadata_given_jsonObject() {
        // given
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, "sysPrompt", null, MODEL_SHORTHAND,
                null, null, null, null, "{\"team\":\"data\"}");

        // when
        Map<String, Object> metadata = version.parseMetadata();

        // then
        assertEquals("data", metadata.get("team"));
        assertEquals(Map.of(), AgentVersion.parseMetadata(null));
    }

    @Test
    void should_throw_when_parseMetadata_given_malformedJson() {
        // given // when // then
        assertThrows(IllegalStateException.class, () -> AgentVersion.parseMetadata("{oops"));
    }
}