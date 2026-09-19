package com.linkroa.deepdataagent.skill.domain.model;

import com.linkroa.deepdataagent.skill.domain.model.enums.SkillSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillAsset} 技能壳领域不变量单测。
 * <p>覆盖：创建即无版本（latestVersion 为 null）、来源与展示名不变量、最新版本指针推进与
 * 版本全删后置 null、元数据归一不可变。</p>
 */
class SkillAssetTest {

    @Test
    void should_haveNoLatestVersion_when_create_given_validFields() {
        // given // when
        SkillAsset asset = SkillAsset.create("skill_abc", "代码评审", SkillSource.CUSTOM, Map.of(), 1L);

        // then
        assertNull(asset.latestVersion());
        assertEquals(SkillSource.CUSTOM, asset.source());
        assertEquals("代码评审", asset.displayTitle());
    }

    @Test
    void should_advanceLatestVersion_when_withLatestVersion_given_firstVersionKey() {
        // given
        SkillAsset asset = SkillAsset.create("skill_abc", "代码评审", SkillSource.CUSTOM, Map.of(), 1L);

        // when
        SkillAsset advanced = asset.withLatestVersion("1759178010641129");

        // then
        assertEquals("1759178010641129", advanced.latestVersion());
        assertEquals(asset.skillId(), advanced.skillId());
        assertEquals(asset.displayTitle(), advanced.displayTitle());
    }

    @Test
    void should_resetLatestVersionToNull_when_withLatestVersion_given_allVersionsRemoved() {
        // given
        SkillAsset asset = SkillAsset.create("skill_abc", "代码评审", SkillSource.CUSTOM, Map.of(), 1L)
                .withLatestVersion("1759178010641129");

        // when（全部版本删除后指针置 null，展示名 / 来源保持不变）
        SkillAsset cleared = asset.withLatestVersion(null);

        // then
        assertNull(cleared.latestVersion());
        assertEquals("代码评审", cleared.displayTitle());
        assertEquals(SkillSource.CUSTOM, cleared.source());
    }

    @Test
    void should_normalizeNullMetadataToEmpty_when_constructor_given_nullMetadata() {
        // given // when
        SkillAsset asset = SkillAsset.create("skill_abc", "n", SkillSource.CUSTOM, null, 1L);

        // then
        assertTrue(asset.metadata().isEmpty());
    }

    @Test
    void should_throwException_when_create_given_invalidSkillIdPrefix() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SkillAsset.create("ws_bad", "n", SkillSource.CUSTOM, Map.of(), 1L));
    }

    @Test
    void should_throwException_when_create_given_blankDisplayTitle() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SkillAsset.create("skill_abc", "  ", SkillSource.CUSTOM, Map.of(), 1L));
    }

    @Test
    void should_throwException_when_create_given_oversizedDisplayTitle() {
        // given // when // then（展示名上限 255）
        assertThrows(IllegalArgumentException.class,
                () -> SkillAsset.create("skill_abc", "x".repeat(256), SkillSource.CUSTOM, Map.of(), 1L));
    }

    @Test
    void should_throwException_when_create_given_nullSource() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SkillAsset.create("skill_abc", "n", null, Map.of(), 1L));
    }

    @Test
    void should_throwException_when_create_given_nullOwner() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SkillAsset.create("skill_abc", "n", SkillSource.CUSTOM, Map.of(), null));
    }
}