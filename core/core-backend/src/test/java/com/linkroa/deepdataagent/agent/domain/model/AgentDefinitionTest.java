package com.linkroa.deepdataagent.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDefinitionTest {

    @Test
    void should_createDefinition_when_create_given_validFields() {
        // given // when
        AgentDefinition definition = AgentDefinition.create("agent-1", "销售助手", "帮助销售团队");

        // then
        assertEquals("agent-1", definition.agentId());
        assertEquals("销售助手", definition.name());
        assertEquals(0, definition.latestVersion());
        assertFalse(definition.archived());
    }

    @Test
    void should_throwException_when_create_given_blankName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> AgentDefinition.create("agent-1", " ", null));
    }

    @Test
    void should_throwException_when_create_given_nameExceeds64Chars() {
        // given
        String longName = "名称".repeat(40);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> AgentDefinition.create("agent-1", longName, null));
    }

    @Test
    void should_throwException_when_create_given_invalidNamePattern() {
        // given
        // 以数字开头、含特殊字符的非法名称

        // when // then
        assertThrows(IllegalArgumentException.class, () -> AgentDefinition.create("agent-1", "1abc!", null));
    }

    @Test
    void should_throwException_when_create_given_descriptionExceeds500Chars() {
        // given
        String longDesc = "d".repeat(501);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> AgentDefinition.create("agent-1", "合法名称", longDesc));
    }

    @Test
    void should_returnArchivedFlag_when_restore_given_archivedFields() {
        // given
        OffsetDateTime archivedAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

        // when
        AgentDefinition definition = AgentDefinition.restore(
                1L, "agent-1", "销售助手", null, true, archivedAt, 2,
                archivedAt, archivedAt, null, null);

        // then
        assertTrue(definition.archived());
        assertEquals(2, definition.latestVersion());
    }
}