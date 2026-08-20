package com.linkroa.deepdataagent.agent.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentVersionTest {

    @Test
    void should_createVersion_when_create_given_validFields() {
        // given // when
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", "初始版本", "你是销售助手",
                "profile-1", "[{\"skillId\":\"s1\",\"version\":1}]", null, null);

        // then
        assertEquals(1, version.versionNumber());
        assertEquals("你是销售助手", version.system());
        assertEquals("v-1", version.versionId());
    }

    @Test
    void should_throwException_when_create_given_zeroVersionNumber() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> AgentVersion.create("v-1", "agent-1", 0, "v0", null,
                        "system", "profile-1", null, null, null));
    }

    @Test
    void should_throwException_when_create_given_blankModelProfileId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> AgentVersion.create("v-1", "agent-1", 1, "v1", null,
                        "system", "", null, null, null));
    }

    @Test
    void should_defaultSystemToEmpty_when_create_given_nullSystem() {
        // given // when
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, null, "profile-1", null, null, null);

        // then
        assertEquals("", version.system());
    }

    @Test
    void should_returnEmptySkills_when_parseSkillRefs_given_blankSkillIds() {
        // given
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, "system", "profile-1", null, null, null);

        // when
        var refs = version.parseSkillRefs();

        // then
        assertTrue(refs.isEmpty());
    }

    @Test
    void should_returnSkillRefs_when_parseSkillRefs_given_jsonSkillIds() {
        // given
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, "system", "profile-1",
                "[{\"skillId\":\"s1\",\"version\":1},{\"skillId\":\"s2\",\"version\":3}]", null, null);

        // when
        var refs = version.parseSkillRefs();

        // then
        assertEquals(2, refs.size());
        assertEquals("s1", refs.get(0).skillId());
        assertEquals(3, refs.get(1).version());
    }

    @Test
    void should_returnEmptyDatasourceIds_when_parseDatasourceIds_given_blankDataSourceIds() {
        // given
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, "system", "profile-1", null, null, null);

        // when
        var ids = version.parseDatasourceIds();

        // then
        assertTrue(ids.isEmpty());
    }

    @Test
    void should_returnDatasourceIds_when_parseDatasourceIds_given_jsonDataSourceIds() {
        // given
        AgentVersion version = AgentVersion.create(
                "v-1", "agent-1", 1, "v1", null, "system", "profile-1",
                "[{\"skillId\":\"s1\",\"version\":1}]", null, "[1,2]");

        // when
        var ids = version.parseDatasourceIds();

        // then
        assertEquals(2, ids.size());
        assertEquals(1L, ids.get(0));
        assertEquals(2L, ids.get(1));
    }
}