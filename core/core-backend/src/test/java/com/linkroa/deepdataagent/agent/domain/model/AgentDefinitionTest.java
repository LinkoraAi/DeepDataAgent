package com.linkroa.deepdataagent.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDefinitionTest {

    @Test
    void should_createDefinition_when_create_given_validFields() {
        // given // when
        AgentDefinition definition = AgentDefinition.create("agent-1", "销售助手", "帮助销售团队", 1L);

        // then
        assertEquals("agent-1", definition.agentId());
        assertEquals("销售助手", definition.name());
        assertEquals(0, definition.latestVersion());
        assertNull(definition.archivedAt());
        assertFalse(definition.isArchived());
    }

    @Test
    void should_throwException_when_create_given_blankName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> AgentDefinition.create("agent-1", " ", null, 1L));
    }

    @Test
    void should_acceptName_when_create_given_nameAt256Boundary() {
        // given（名称上限 256：契约允许的最大长度必须被接受）
        String boundaryName = "a".repeat(256);

        // when
        AgentDefinition definition = AgentDefinition.create("agent-1", boundaryName, null, 1L);

        // then
        assertEquals(256, definition.name().length());
    }

    @Test
    void should_throwException_when_create_given_nameExceeds256Chars() {
        // given（越界 1 字符即拒）
        String longName = "a".repeat(257);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> AgentDefinition.create("agent-1", longName, null, 1L));
    }

    @Test
    void should_acceptDescription_when_create_given_descriptionAt2048Boundary() {
        // given（描述上限 2048：契约允许的最大长度必须被接受）
        String boundaryDesc = "d".repeat(2048);

        // when
        AgentDefinition definition = AgentDefinition.create("agent-1", "合法名称", boundaryDesc, 1L);

        // then
        assertEquals(2048, definition.description().length());
    }

    @Test
    void should_throwException_when_create_given_descriptionExceeds2048Chars() {
        // given
        String longDesc = "d".repeat(2049);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> AgentDefinition.create("agent-1", "合法名称", longDesc, 1L));
    }

    @Test
    void should_returnArchivedState_when_restore_given_archivedAt() {
        // given
        OffsetDateTime archivedAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

        // when
        AgentDefinition definition = AgentDefinition.restore(
                1L, "agent-1", "销售助手", null, archivedAt, 2, 2, 1L,
                archivedAt, archivedAt, null, null);

        // then
        assertTrue(definition.isArchived());
        assertEquals(archivedAt, definition.archivedAt());
        assertEquals(2, definition.latestVersion());
        assertEquals(2, definition.activeVersion());
    }

    @Test
    void should_syncActiveVersionToLatest_when_withLatestVersion_given_publish() {
        // given
        AgentDefinition definition = AgentDefinition.create("agent-1", "销售助手", null, 1L);

        // when
        AgentDefinition published = definition.withLatestVersion(3);

        // then
        assertEquals(3, published.latestVersion());
        assertEquals(3, published.activeVersion());
    }

    @Test
    void should_keepLatestVersion_when_withActivatedVersion_given_rollbackWithinLedger() {
        // given（已发布 3 版、当前激活 3 版）
        AgentDefinition definition = AgentDefinition.restore(1L, "agent-1", "销售助手", null,
                null, 3, 3, 1L, null, null, null, null);

        // when（回滚到第 1 版）
        AgentDefinition rolledBack = definition.withActivatedVersion(1);

        // then（仅激活指针切换，最新台账不变）
        assertEquals(3, rolledBack.latestVersion());
        assertEquals(1, rolledBack.activeVersion());
        // 原对象不可变
        assertEquals(3, definition.activeVersion());
    }

    @Test
    void should_throwException_when_withActivatedVersion_given_outOfLedgerRange() {
        // given
        AgentDefinition definition = AgentDefinition.restore(1L, "agent-1", "销售助手", null,
                null, 3, 3, 1L, null, null, null, null);

        // when // then（0 / 超过最新号的越界激活一律拒绝）
        assertThrows(IllegalArgumentException.class, () -> definition.withActivatedVersion(0));
        assertThrows(IllegalArgumentException.class, () -> definition.withActivatedVersion(4));
    }

    @Test
    void should_throwException_when_withActivatedVersion_given_unpublishedAgent() {
        // given（尚未发布任何版本：无可激活目标）
        AgentDefinition definition = AgentDefinition.create("agent-1", "销售助手", null, 1L);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> definition.withActivatedVersion(1));
    }
}
