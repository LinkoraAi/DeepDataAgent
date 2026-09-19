package com.linkroa.deepdataagent.agent.infrastructure.convert;

import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentDefinitionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentVersionEntity;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentPersistenceConvert} 持久化转换器单测（Agent 定义 / 版本 ⇄ 实体双向映射）。
 * <p>归档以 {@code archived_at} 时间戳单列表达（无 archived 布尔列）；AGENTS.md 列已废止。</p>
 */
class AgentPersistenceConvertTest {

    private final AgentPersistenceConvert convert = AgentPersistenceConvert.INSTANCE;

    private final OffsetDateTime now = OffsetDateTime.parse("2026-08-22T10:00:00+08:00");

    // ==================== AgentDefinition ⇄ Entity ====================

    @Test
    void should_mapAllFields_when_toEntity_given_fullAgentDefinition() {
        // given（已归档态 + 全部基础字段）
        AgentDefinition definition = AgentDefinition.restore(
                1L, "agent-a", "数据分析助手", "描述", now,
                5, 4, 2L, now, now, "u-1", "u-1");

        // when
        AgentDefinitionEntity entity = convert.toEntity(definition);

        // then
        assertEquals(1L, entity.getId());
        assertEquals("agent-a", entity.getAgentId());
        assertEquals("数据分析助手", entity.getName());
        assertEquals("描述", entity.getDescription());
        assertEquals(now, entity.getArchivedAt());
        assertEquals(5, entity.getLatestVersion());
        assertEquals(4, entity.getActiveVersion());
        assertEquals(2L, entity.getOwnerId());
        assertEquals(now, entity.getCreatedAt());
        assertEquals("u-1", entity.getCreatedBy());
        assertEquals("u-1", entity.getUpdatedBy());
    }

    @Test
    void should_restoreDefinition_when_toDomain_given_entityWithoutOptionalFields() {
        // given（未归档 / 无审计值）
        AgentDefinitionEntity entity = new AgentDefinitionEntity();
        entity.setId(9L);
        entity.setAgentId("agent-b");
        entity.setName("基础Agent");
        entity.setArchivedAt(null);
        entity.setLatestVersion(2);
        entity.setActiveVersion(1);
        entity.setOwnerId(3L);

        // when
        AgentDefinition definition = convert.toDomain(entity);

        // then
        assertEquals(9L, definition.id());
        assertEquals("agent-b", definition.agentId());
        assertEquals("基础Agent", definition.name());
        assertFalse(definition.isArchived());
        assertEquals(2, definition.latestVersion());
        assertEquals(1, definition.activeVersion());
        assertEquals(3L, definition.ownerId());
        assertNull(definition.archivedAt());
    }

    // ==================== AgentVersion ⇄ Entity ====================

    @Test
    void should_mapAllFields_when_toEntity_given_agentVersionWithInlineRecipes() {
        // given（内联配方 + 模型引用全量填充）
        AgentVersion version = AgentVersion.restore(
                7L, "ver-1", "agent-a", 3, "v3.0", "版本描述", "你是助手", "profile-1",
                "{\"id\":\"ultimate\",\"effort\":\"high\"}",
                "[{\"name\":\"search\"}]", "[{\"type\":\"http\"}]",
                "[{\"type\":\"custom\",\"skill_id\":\"skill-a\",\"version\":\"1759178010641129\"}]",
                "{\"enabled\":true}", "{\"team\":\"data\"}",
                now, now, "u-1", "u-1");

        // when
        AgentVersionEntity entity = convert.toEntity(version);

        // then（JSONB 字符串透传 + 可空值保真）
        assertEquals(7L, entity.getId());
        assertEquals("ver-1", entity.getVersionId());
        assertEquals("agent-a", entity.getAgentId());
        assertEquals(3, entity.getVersionNumber());
        assertEquals("v3.0", entity.getName());
        assertEquals("版本描述", entity.getDescription());
        assertEquals("你是助手", entity.getSystemPrompt());
        assertEquals("profile-1", entity.getModelProfileId());
        assertEquals("{\"id\":\"ultimate\",\"effort\":\"high\"}", entity.getModelJson());
        assertEquals("{\"team\":\"data\"}", entity.getMetadataJson());
        assertEquals("[{\"name\":\"search\"}]", entity.getToolsJson());
        assertEquals("[{\"type\":\"http\"}]", entity.getMcpServersJson());
        assertEquals("[{\"type\":\"custom\",\"skill_id\":\"skill-a\",\"version\":\"1759178010641129\"}]",
                entity.getSkillsJson());
        assertEquals(now, entity.getCreatedAt());
    }

    @Test
    void should_roundTripVersion_when_toDomain_given_entity() {
        // given（缺省字段保留 null：结构化 JSON 列）
        AgentVersionEntity entity = new AgentVersionEntity();
        entity.setId(2L);
        entity.setVersionId("ver-2");
        entity.setAgentId("agent-c");
        entity.setVersionNumber(1);
        entity.setName("v1");
        entity.setSystemPrompt("");
        entity.setModelProfileId("profile-2");
        entity.setModelJson("{\"id\":\"ultimate\"}");

        // when
        AgentVersion version = convert.toDomain(entity);

        // then
        assertEquals(2L, version.id());
        assertEquals("ver-2", version.versionId());
        assertEquals("agent-c", version.agentId());
        assertEquals(1, version.versionNumber());
        assertEquals("profile-2", version.modelProfileId());
        assertEquals("{\"id\":\"ultimate\"}", version.modelJson());
        assertNull(version.toolsJson());
        assertNull(version.skillsJson());
        assertNull(version.metadataJson());
    }

    @Test
    void should_returnNullJsonColumns_when_toDomain_given_legacyRowWithoutRecipes() {
        // given（历史版本行未配置 JSONB 配方：列为 NULL，反序列化为 null 而非抛异常）
        AgentVersionEntity entity = new AgentVersionEntity();
        entity.setId(3L);
        entity.setVersionId("ver-3");
        entity.setAgentId("agent-d");
        entity.setVersionNumber(1);
        entity.setName("v1");
        entity.setSystemPrompt("你是助手");
        entity.setModelProfileId("profile-3");

        // when
        AgentVersion version = convert.toDomain(entity);

        // then
        assertNull(version.modelJson());
        assertNull(version.toolsJson());
        assertTrue(version.parseTools().isEmpty());
    }
}