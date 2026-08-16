package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStorageType;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillType;
import com.linkroa.deepdataagent.agent.infrastructure.util.Sha256Util;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 技能资源领域模型不变量单测
 */
class SkillResourceTest {

    private byte[] content() {
        return "技能包内容".getBytes(StandardCharsets.UTF_8);
    }

    private String sha256() {
        return Sha256Util.hex(content());
    }

    @Test
    void should_createActiveVersion_when_create_given_validFields() {
        // when
        SkillResource skill = SkillResource.create(
                "skill-1", 1, "数据清洗", "描述", SkillType.CUSTOM,
                SkillStorageType.LOCAL_FILE, "skill-1/1/skill-1-1.zip", sha256(), content().length);

        // then
        assertEquals(1, skill.versionNumber());
        assertEquals("数据清洗", skill.name());
        assertEquals(SkillStatus.ACTIVE, skill.status());
        assertEquals(SkillType.CUSTOM, skill.skillType());
    }

    @Test
    void should_throw_when_create_given_blankName() {
        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> SkillResource.create(
                "skill-1", 1, "  ", null, SkillType.CUSTOM,
                SkillStorageType.LOCAL_FILE, "k", sha256(), 1));
    }

    @Test
    void should_throw_when_create_given_versionLessThanOne() {
        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> SkillResource.create(
                "skill-1", 0, "数据清洗", null, SkillType.CUSTOM,
                SkillStorageType.LOCAL_FILE, "k", sha256(), 1));
    }

    @Test
    void should_throw_when_create_given_illegalSha256() {
        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> SkillResource.create(
                "skill-1", 1, "数据清洗", null, SkillType.CUSTOM,
                SkillStorageType.LOCAL_FILE, "k", "not-a-sha256", 1));
    }

    @Test
    void should_throw_when_create_given_blankSkillId() {
        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> SkillResource.create(
                "  ", 1, "数据清洗", null, SkillType.CUSTOM,
                SkillStorageType.LOCAL_FILE, "k", sha256(), 1));
    }

    @Test
    void should_restore_when_restore_given_allFields() {
        // given
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

        // when
        SkillResource skill = SkillResource.restore(
                1L, "skill-1", 1, "数据清洗", "描述", SkillType.CUSTOM,
                SkillStorageType.LOCAL_FILE, "k", sha256(), 10, SkillStatus.REJECTED,
                now, now, "admin", "admin");

        // then
        assertEquals(1L, skill.id());
        assertEquals(SkillStatus.REJECTED, skill.status());
        assertEquals("admin", skill.createdBy());
    }
}