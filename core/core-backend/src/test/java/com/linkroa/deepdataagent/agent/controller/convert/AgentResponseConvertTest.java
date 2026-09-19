package com.linkroa.deepdataagent.agent.controller.convert;

import com.linkroa.deepdataagent.agent.controller.response.AgentResponse;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentResponseConvert} 转换单测（Agent 对象与版本快照同形：{@code *Json} 配置列按提交形态
 * 回显为结构化 JSON 值，未配置时为空数组 / 空对象；落库文本形状非法即失败，不静默降级；
 * AGENTS.md / latest_version / active_version 等已废止或内部字段不出现在契约）。
 */
class AgentResponseConvertTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T10:00:00+08:00");

    @Test
    void should_mapJsonbFieldsToStructuredValues_when_toVersionResponse_given_configuredSnapshot() {
        // given（四个 JSONB 配置列均有内容）
        AgentVersion version = snapshot(
                "[{\"type\":\"mcp_toolset\",\"mcp_server_name\":\"fs\"}]",
                "[{\"name\":\"fs\",\"type\":\"url\",\"url\":\"https://mcp.example.com/sse\"}]",
                "[{\"type\":\"catalog\",\"skill_id\":\"skill_1\"}]",
                "{\"team\":\"data\"}");

        // when
        AgentResponse response = AgentResponseConvert.INSTANCE.toVersionResponse(version, null);

        // then（结构化数组/对象，调用方无需二次解析）
        assertEquals(1, response.tools().size());
        assertEquals("mcp_toolset", asMap(response.tools().getFirst()).get("type"));
        assertEquals(1, response.mcp_servers().size());
        assertEquals("fs", asMap(response.mcp_servers().getFirst()).get("name"));
        assertEquals("https://mcp.example.com/sse", asMap(response.mcp_servers().getFirst()).get("url"));
        assertEquals("skill_1", asMap(response.skills().getFirst()).get("skill_id"));
        assertEquals(Map.of("team", "data"), response.metadata());
    }

    @Test
    void should_returnEmptyContainers_when_toVersionResponse_given_blankJsonbFields() {
        // given（四个配置列均未配置）
        AgentVersion version = snapshot(null, null, null, null);

        // when
        AgentResponse response = AgentResponseConvert.INSTANCE.toVersionResponse(version, null);

        // then（形状稳定：数组恒为空数组、对象恒为空对象）
        assertEquals(List.of(), response.tools());
        assertEquals(List.of(), response.mcp_servers());
        assertEquals(List.of(), response.skills());
        assertEquals(Map.of(), response.metadata());
    }

    @Test
    void should_echoSubmittedForm_when_toVersionResponse_given_stringAndObjectModel() {
        // given（字符串简写与对象形态两种等价提交）
        AgentVersion shorthand = snapshot(null, null, null, null);
        AgentVersion objectForm = AgentVersion.restore(
                1L, "ver_2", "agent_1", 2, "v2", null, "系统提示词", null,
                "{\"id\":\"ultimate\",\"effort\":\"high\"}", null, null, null, null, null,
                NOW, NOW, "1", "1");

        // when
        AgentResponse shorthandResponse = AgentResponseConvert.INSTANCE.toVersionResponse(shorthand, null);
        AgentResponse objectResponse = AgentResponseConvert.INSTANCE.toVersionResponse(objectForm, null);

        // then
        assertEquals("ultimate", shorthandResponse.model());
        assertEquals("high", asMap(objectResponse.model()).get("effort"));
    }

    @Test
    void should_mapSystemPromptAndOmitAgentsMd_when_toVersionResponse_given_systemConfigured() {
        // given（系统提示词为版本快照唯一指令载体；AGENTS.md 已废止）
        AgentVersion version = snapshot(null, null, null, null);
        ObjectMapper mapper = JsonMapper.builder().build();

        // when
        AgentResponse response = AgentResponseConvert.INSTANCE.toVersionResponse(version, null);

        // then（system 回显；序列化不含 agents_md 键）
        assertEquals("系统提示词", response.system());
        assertFalse(mapper.writeValueAsString(response).contains("agents_md"));
    }

    @Test
    void should_serializeContractFieldNames_when_serialize_given_versionSnapshot() {
        // given（对外字段名 MUST 为 id / type / mcp_servers / version / archived_at / created_at / updated_at）
        AgentResponse response = AgentResponseConvert.INSTANCE.toVersionResponse(
                snapshot(null, "[{\"name\":\"fs\",\"type\":\"url\",\"url\":\"https://mcp.example.com/sse\"}]",
                        null, null), null);
        ObjectMapper mapper = JsonMapper.builder().build();

        // when
        String json = mapper.writeValueAsString(response);

        // then
        assertTrue(json.contains("\"mcp_servers\":["));
        assertTrue(json.contains("\"archived_at\":"));
        assertTrue(json.contains("\"created_at\":"));
        assertTrue(json.contains("\"updated_at\":"));
        assertTrue(json.contains("\"version\":1"));
        // 内部版本台账字段与旧命名一律不外泄
        assertFalse(json.contains("latest_version"));
        assertFalse(json.contains("active_version"));
        assertFalse(json.contains("version_id"));
        assertFalse(json.contains("agent_id"));
        assertFalse(json.contains("version_number"));
    }

    @Test
    void should_carryAgentIdAndSnapshotNumber_when_toVersionResponse_given_snapshot() {
        // given（版本快照对外以 id=Agent 业务 ID + version=发布号表达）
        AgentVersion version = snapshot(null, null, null, null);

        // when
        AgentResponse response = AgentResponseConvert.INSTANCE.toVersionResponse(version, NOW);

        // then
        assertEquals("agent_1", response.id());
        assertEquals("agent", response.type());
        assertEquals(1, response.version());
        assertEquals(NOW, response.archived_at());
    }

    @Test
    void should_mapMultiagentToStructuredObject_when_toVersionResponse_given_coordinatorSnapshot() {
        // given（多智能体编排配置为 coordinator + agents[] 对象简写）
        AgentVersion version = AgentVersion.restore(
                1L, "ver_1", "agent_1", 1, "v1", "首版", "系统提示词", null,
                "\"ultimate\"", null, null, null,
                "{\"type\":\"coordinator\",\"agents\":[{\"name\":\"child\"}]}", null,
                NOW, NOW, "1", "1");

        // when
        AgentResponse response = AgentResponseConvert.INSTANCE.toVersionResponse(version, null);

        // then（结构化回显，非 JSON 字符串）
        assertEquals("coordinator", response.multiagent().get("type"));
        assertInstanceOf(List.class, response.multiagent().get("agents"));
        // 未配置时保持 null（契约声明可空）
        assertNull(AgentResponseConvert.INSTANCE.toVersionResponse(snapshot(null, null, null, null), null).multiagent());
    }

    @Test
    void should_mergeDefinitionHeadWithCurrentVersionConfig_when_toResponse_given_definitionWithActiveVersion() {
        // given（定义属性权威 + 当前生效版本配置快照）
        AgentDefinition definition = definition(null, 3, 2);
        AgentVersion currentVersion = AgentVersion.restore(
                2L, "ver_2", "agent_1", 2, "v2", "第二版描述", "第二版提示词", null,
                "\"ultimate\"", "[{\"type\":\"agent_toolset_20260401\"}]", null, null, null,
                "{\"team\":\"data\"}", NOW, NOW, "1", "1");

        // when
        AgentResponse response = AgentResponseConvert.INSTANCE.toResponse(definition, currentVersion);

        // then（头部字段取定义，配置字段与 version 取当前生效版本）
        assertEquals("agent_1", response.id());
        assertEquals("agent", response.type());
        assertEquals("分析助手", response.name());
        // 定义描述为权威值：当前生效版本的描述不覆盖定义
        assertEquals("说明", response.description());
        assertEquals("第二版提示词", response.system());
        assertEquals(2, response.version());
        assertEquals(1, response.tools().size());
        assertEquals(Map.of("team", "data"), response.metadata());
        assertEquals(definition.createdAt(), response.created_at());
        assertEquals(definition.updatedAt(), response.updated_at());
        assertNull(response.archived_at());
    }

    @Test
    void should_fallbackToLatestVersionAndEmptyContainers_when_toResponse_given_noVersionSnapshot() {
        // given（版本台账为空：active/latest 均为 0）
        AgentDefinition definition = definition(null, 0, 0);

        // when
        AgentResponse response = AgentResponseConvert.INSTANCE.toResponse(definition, null);

        // then（配置位取空形状，version 回落 latest_version）
        assertEquals(0, response.version());
        assertNull(response.model());
        assertNull(response.system());
        assertEquals(List.of(), response.tools());
        assertEquals(List.of(), response.mcp_servers());
        assertEquals(List.of(), response.skills());
        assertEquals(Map.of(), response.metadata());
        assertNull(response.multiagent());
    }

    @Test
    void should_exposeArchivedAtWithoutBoolean_when_toResponse_given_archivedDefinition() {
        // given（归档仅以 archived_at 时间戳表达）
        AgentDefinition definition = definition(NOW, 1, 1);

        // when
        AgentResponse response = AgentResponseConvert.INSTANCE.toResponse(definition, snapshot(null, null, null, null));

        // then
        assertEquals(NOW, response.archived_at());
    }

    @Test
    void should_throwIllegalState_when_toVersionResponse_given_toolsNotArray() {
        // given（落库文本形状非法：期望数组却为对象）
        AgentVersion version = snapshot("{\"type\":\"custom\"}", null, null, null);

        // when // then（不静默降级为空数组，暴露脏数据）
        assertThrows(IllegalStateException.class,
                () -> AgentResponseConvert.INSTANCE.toVersionResponse(version, null));
    }

    @Test
    void should_throwIllegalState_when_toVersionResponse_given_metadataNotObject() {
        // given（落库文本形状非法：期望对象却为数组）
        AgentVersion version = snapshot(null, null, null, "[1,2]");

        // when // then
        assertThrows(IllegalStateException.class,
                () -> AgentResponseConvert.INSTANCE.toVersionResponse(version, null));
    }

    /** Agent 定义装配（active=2/latest=3 表示激活版本被回滚过）。 */
    private static AgentDefinition definition(OffsetDateTime archivedAt, int latestVersion, int activeVersion) {
        return AgentDefinition.restore(
                1L, "agent_1", "分析助手", "说明", archivedAt, latestVersion, activeVersion, 9L,
                NOW, NOW, "9", "9");
    }

    /** 版本快照装配（模型引用固定为字符串简写，只需变更四个 JSONB 配置列）。 */
    private static AgentVersion snapshot(String toolsJson, String mcpServersJson,
                                         String skillsJson, String metadataJson) {
        return AgentVersion.restore(
                1L, "ver_1", "agent_1", 1, "v1", "首版", "系统提示词", null,
                "\"ultimate\"", toolsJson, mcpServersJson, skillsJson, null, metadataJson,
                NOW, NOW, "1", "1");
    }

    /** 结构化 JSON 值断言为键值对象（避免直接强转带来的类型盲区）。 */
    private static Map<?, ?> asMap(Object value) {
        return assertInstanceOf(Map.class, value);
    }
}