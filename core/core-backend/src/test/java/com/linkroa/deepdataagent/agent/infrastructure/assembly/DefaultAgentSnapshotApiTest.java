package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.api.dto.AgentSnapshotDTO;
import com.linkroa.deepdataagent.agent.application.service.ModelCatalogService;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.model.ModelCatalogItem;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelEffort;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultAgentSnapshotApi} 裁剪规则单测：audit / metadata 剔除、system 输出、
 * 缺行降级 null、{@code model.effective_context_window} 只读字段计算、
 * {@code multiagent} 恒 null（不展开 coordinator {@code agents[]} 阵列）、
 * 归属隔离 fail-closed（审查修复 F05：ownerId 缺失 / 越权不返回快照）。
 */
@ExtendWith(MockitoExtension.class)
class DefaultAgentSnapshotApiTest {

    @Mock private AgentDefinitionRepository definitionRepository;
    @Mock private AgentVersionRepository versionRepository;
    @Mock private ModelCatalogService modelCatalogService;

    private DefaultAgentSnapshotApi api;

    @BeforeEach
    void setUp() {
        api = new DefaultAgentSnapshotApi();
        ReflectionTestUtils.setField(api, "definitionRepository", definitionRepository);
        ReflectionTestUtils.setField(api, "versionRepository", versionRepository);
        ReflectionTestUtils.setField(api, "modelCatalogService", modelCatalogService);
    }

    @Test
    void should_returnNull_when_resolveSnapshot_given_missingAgent() {
        // given（台账缺行：Agent 不存在 → null，消费方降级摘要）
        when(definitionRepository.findByAgentId("agent-x")).thenReturn(Optional.empty());

        // when & then
        assertNull(api.resolveSnapshot("agent-x", 1, 1L));
    }

    @Test
    void should_returnNull_when_resolveSnapshot_given_missingVersion() {
        // given（有定义无版本行）
        when(definitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(definition("agent-1", 3, 1L)));
        when(versionRepository.findByAgentIdAndVersionNumber("agent-1", 3)).thenReturn(Optional.empty());

        // when & then
        assertNull(api.resolveSnapshot("agent-1", 3, 1L));
    }

    @Test
    void should_returnNull_when_resolveSnapshot_given_nullOwnerId() {
        // given（审查修复 F05：ownerId 缺失 fail-closed，不查台账也不返回任何快照）
        // when & then
        assertNull(api.resolveSnapshot("agent-1", 1, null));
    }

    @Test
    void should_returnNull_when_resolveSnapshot_given_foreignDefinition() {
        // given（定义存在但归属他人 → 与不存在不可区分，降级摘要不出内容）
        when(definitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(definition("agent-1", 2, 999L)));

        // when & then
        assertNull(api.resolveSnapshot("agent-1", 2, 1L));
    }

    @Test
    void should_trimAuditAndMetadata_when_resolveSnapshot_given_simpleAgent() {
        // given（单 Agent：模型简写 + 工具数组，metadata / audit 须裁剪）
        when(definitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(definition("agent-1", 2, 1L)));
        when(versionRepository.findByAgentIdAndVersionNumber("agent-1", 2)).thenReturn(Optional.of(
                version("agent-1", 2, "分析助手", "\"openai:gpt-4\"", "[{\"name\":\"read_file\"}]", null)));

        // when
        AgentSnapshotDTO dto = api.resolveSnapshot("agent-1", 2, 1L);

        // then
        assertNotNull(dto);
        Map<String, Object> agent = dto.agent();
        assertEquals("agent-1", agent.get("id"));
        assertEquals("agent", agent.get("type"));
        assertEquals(2, agent.get("version"));
        assertEquals("分析助手", agent.get("name"));
        assertEquals("", agent.get("system"));
        // 字符串简写形态且目录无默认窗口时原样回显
        assertEquals("openai:gpt-4", agent.get("model"));
        assertFalse(agent.containsKey("metadata"));
        assertFalse(agent.containsKey("created_at"));
        assertFalse(agent.containsKey("updated_at"));
        assertFalse(agent.containsKey("archived"));
        assertFalse(agent.containsKey("archived_at"));
        assertFalse(agent.containsKey("agents_md"));
        assertEquals(List.of(Map.of("name", "read_file")), agent.get("tools"));
        // 按提交形态回显：台账未写 mcp_servers / skills 列（空文本）即省略键
        assertFalse(agent.containsKey("mcp_servers"));
        assertFalse(agent.containsKey("skills"));
    }

    @Test
    void should_emitSystemKey_when_resolveSnapshot_given_sysPrompt() {
        // given（系统提示词以 system 输出，instructions 概念不外露）
        when(definitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(definition("agent-1", 1, 1L)));
        AgentVersion version = AgentVersion.create("ver-agent-1-1", "agent-1", 1, "助手",
                "描述", "你是数据分析专家", null, "\"openai:gpt-4\"", null, null, null, null, null);
        when(versionRepository.findByAgentIdAndVersionNumber("agent-1", 1)).thenReturn(Optional.of(version));

        // when
        Map<String, Object> agent = api.resolveSnapshot("agent-1", 1, 1L).agent();

        // then
        assertEquals("你是数据分析专家", agent.get("system"));
        assertFalse(agent.containsKey("instructions"));
    }

    @Test
    void should_appendEffectiveContextWindow_when_resolveSnapshot_given_catalogDefaultWindow() {
        // given（字符串简写模型引用：生效窗口取模型目录默认值，展开为对象承载只读字段）
        when(definitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(definition("agent-1", 1, 1L)));
        when(versionRepository.findByAgentIdAndVersionNumber("agent-1", 1)).thenReturn(Optional.of(
                version("agent-1", 1, "助手", "\"ultimate\"", null, null)));
        when(modelCatalogService.find("ultimate")).thenReturn(Optional.of(catalogItem()));

        // when
        Map<String, Object> agent = api.resolveSnapshot("agent-1", 1, 1L).agent();

        // then
        Map<?, ?> model = (Map<?, ?>) agent.get("model");
        assertEquals("ultimate", model.get("id"));
        assertEquals(128000, model.get("effective_context_window"));
    }

    @Test
    void should_preferExplicitContextWindow_when_resolveSnapshot_given_objectModel() {
        // given（对象形态携带显式 context_window：不再查目录）
        when(definitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(definition("agent-1", 1, 1L)));
        when(versionRepository.findByAgentIdAndVersionNumber("agent-1", 1)).thenReturn(Optional.of(
                version("agent-1", 1, "助手",
                        "{\"id\":\"ultimate\",\"context_window\":200000}", null, null)));

        // when
        Map<String, Object> agent = api.resolveSnapshot("agent-1", 1, 1L).agent();

        // then
        Map<?, ?> model = (Map<?, ?>) agent.get("model");
        assertEquals(200000, model.get("effective_context_window"));
        verify(modelCatalogService, never()).find(anyString());
    }

    @Test
    void should_emitNullMultiagent_when_resolveSnapshot_given_coordinatorVersion() {
        // given（台账残留 coordinator 配置：快照 MUST 恒为 null，不得展开 agents[] 阵列）
        when(definitionRepository.findByAgentId("agent-main"))
                .thenReturn(Optional.of(definition("agent-main", 1, 1L)));
        when(versionRepository.findByAgentIdAndVersionNumber(eq("agent-main"), anyInt())).thenReturn(
                Optional.of(version("agent-main", 1, "协调器", "\"openai:gpt-4\"", null,
                        "{\"type\":\"coordinator\",\"agents\":[{\"agent_id\":\"agent-child\",\"version\":5}]}")));

        // when
        Map<String, Object> agent = api.resolveSnapshot("agent-main", 1, 1L).agent();

        // then（键存在且为 null；不查子定义、不产出 agents 阵列）
        assertTrue(agent.containsKey("multiagent"));
        assertNull(agent.get("multiagent"));
        verify(definitionRepository, never()).findByAgentId("agent-child");
    }

    @Test
    void should_emitNullMultiagent_when_resolveSnapshot_given_simpleAgent() {
        // given（无 multiagent 配置：键仍恒存在且为 null，不是缺键）
        when(definitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(definition("agent-1", 1, 1L)));
        when(versionRepository.findByAgentIdAndVersionNumber("agent-1", 1)).thenReturn(Optional.of(
                version("agent-1", 1, "助手", "\"openai:gpt-4\"", null, null)));

        // when
        Map<String, Object> agent = api.resolveSnapshot("agent-1", 1, 1L).agent();

        // then
        assertTrue(agent.containsKey("multiagent"));
        assertNull(agent.get("multiagent"));
    }

    @Test
    void should_preferActiveVersion_when_resolveSnapshot_given_noExplicitVersion() {
        // given（latest=3 但 active=1：版本号缺省 MUST 解析激活版本，与 Agent 读取路径口径一致）
        when(definitionRepository.findByAgentId("agent-1")).thenReturn(Optional.of(
                AgentDefinition.restore(1L, "agent-1", "助手", null, null, 3, 1, 1L, null, null, null, null)));
        when(versionRepository.findByAgentIdAndVersionNumber("agent-1", 1)).thenReturn(Optional.of(
                version("agent-1", 1, "助手", "\"openai:gpt-4\"", null, null)));

        // when
        Map<String, Object> agent = api.resolveSnapshot("agent-1", null, 1L).agent();

        // then
        assertEquals(1, agent.get("version"));
        verify(versionRepository, never()).findByAgentIdAndVersionNumber("agent-1", 3);
    }

    @Test
    void should_fallBackToLatestVersion_when_resolveSnapshot_given_noActiveVersion() {
        // given（active=0 = 无激活版本：回落最新已发布版本）
        when(definitionRepository.findByAgentId("agent-1")).thenReturn(Optional.of(
                AgentDefinition.restore(1L, "agent-1", "助手", null, null, 3, 0, 1L, null, null, null, null)));
        when(versionRepository.findByAgentIdAndVersionNumber("agent-1", 3)).thenReturn(Optional.of(
                version("agent-1", 3, "助手", "\"openai:gpt-4\"", null, null)));

        // when
        Map<String, Object> agent = api.resolveSnapshot("agent-1", null, 1L).agent();

        // then
        assertEquals(3, agent.get("version"));
    }

    private static AgentDefinition definition(String agentId, int latestVersion, Long ownerId) {
        return AgentDefinition.restore(1L, agentId, "助手", null, null,
                latestVersion, latestVersion, ownerId, null, null, null, null);
    }

    private static AgentVersion version(String agentId, int number, String name,
                                        String modelJson, String toolsJson, String multiagent) {
        return AgentVersion.create("ver-" + agentId + "-" + number, agentId, number, name,
                "描述", "", null, modelJson, toolsJson, null, null, multiagent, "{\"k\":\"v\"}");
    }

    /** 目录模型条目（默认上下文窗口 128000）。 */
    private static ModelCatalogItem catalogItem() {
        return new ModelCatalogItem("ultimate", "旗舰模型", ModelCatalogItem.SOURCE_SYSTEM, true,
                false, false, false,
                List.of(ModelEffort.from("low"), ModelEffort.from("medium"), ModelEffort.from("high")),
                ModelEffort.from("medium"), 200000, 8000, 128000, List.of(128000, 200000));
    }
}