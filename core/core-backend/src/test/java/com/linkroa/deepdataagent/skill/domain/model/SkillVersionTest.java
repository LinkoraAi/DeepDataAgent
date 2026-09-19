package com.linkroa.deepdataagent.skill.domain.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillVersion} 技能内容版本领域不变量单测。
 * <p>覆盖：epoch 微秒版本键、frontmatter name 正则与边界、目录名恒等于 name、
 * 校验值格式与大小非负、资源清单归一不可变。</p>
 */
class SkillVersionTest {

    private static final String SHA = "0".repeat(64);
    private static final String VERSION_ID = "skillver_1";
    private static final String SKILL_ID = "skill_abc";
    private static final String VERSION_KEY = "1759178010641129";
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-08-22T10:00:00+08:00");

    @Test
    void should_holdFields_when_create_given_validPackageMetadata() {
        // given // when
        SkillVersion version = SkillVersion.create(VERSION_ID, SKILL_ID, VERSION_KEY, "code-review",
                "代码评审技能", SHA, 12L, Map.of("references/a.md", 5L));

        // then
        assertEquals(VERSION_KEY, version.version());
        assertEquals("code-review", version.name());
        assertEquals("code-review", version.directory());
        assertEquals(12L, version.contentSize());
        assertEquals(1, version.resources().size());
    }

    @Test
    void should_throwException_when_create_given_missingVersionIdPrefix() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SkillVersion.create("1", SKILL_ID, VERSION_KEY, "n", "d", SHA, 1L, Map.of()));
    }

    @Test
    void should_throwException_when_create_given_nonNumericVersionKey() {
        // given // when // then（版本键必须为十进制 epoch 微秒串）
        assertThrows(IllegalArgumentException.class,
                () -> SkillVersion.create(VERSION_ID, SKILL_ID, "latest", "n", "d", SHA, 1L, Map.of()));
    }

    @Test
    void should_throwException_when_create_given_uppercaseName() {
        // given // when // then（名称须匹配 ^[a-z0-9][a-z0-9_-]*$）
        assertThrows(IllegalArgumentException.class,
                () -> SkillVersion.create(VERSION_ID, SKILL_ID, VERSION_KEY, "CodeReview", "d", SHA, 1L, Map.of()));
    }

    @Test
    void should_throwException_when_restore_given_directoryDiffersFromName() {
        // given // when // then（目录名恒等于 frontmatter name）
        assertThrows(IllegalArgumentException.class,
                () -> SkillVersion.restore(VERSION_ID, SKILL_ID, VERSION_KEY, "code-review", "d", "other",
                        SHA, 1L, Map.of(), CREATED_AT));
    }

    @Test
    void should_throwException_when_create_given_oversizedName() {
        // given // when // then（名称上限 64）
        assertThrows(IllegalArgumentException.class,
                () -> SkillVersion.create(VERSION_ID, SKILL_ID, VERSION_KEY, "a".repeat(65), "d", SHA, 1L, Map.of()));
    }

    @Test
    void should_throwException_when_create_given_blankDescription() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SkillVersion.create(VERSION_ID, SKILL_ID, VERSION_KEY, "n", "  ", SHA, 1L, Map.of()));
    }

    @Test
    void should_throwException_when_create_given_oversizedDescription() {
        // given // when // then（描述上限 5120）
        assertThrows(IllegalArgumentException.class,
                () -> SkillVersion.create(VERSION_ID, SKILL_ID, VERSION_KEY, "n", "d".repeat(5121), SHA, 1L, Map.of()));
    }

    @Test
    void should_throwException_when_create_given_invalidSha() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SkillVersion.create(VERSION_ID, SKILL_ID, VERSION_KEY, "n", "d", "abc", 1L, Map.of()));
    }

    @Test
    void should_throwException_when_create_given_negativeSize() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SkillVersion.create(VERSION_ID, SKILL_ID, VERSION_KEY, "n", "d", SHA, -1L, Map.of()));
    }

    @Test
    void should_normalizeNullResources_when_create_given_nullResources() {
        // given // when
        SkillVersion version = SkillVersion.create(VERSION_ID, SKILL_ID, VERSION_KEY, "n", "d", SHA, 1L, null);

        // then
        assertTrue(version.resources().isEmpty());
    }
}