package com.linkroa.deepdataagent.skill.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SkillSource} 领域枚举单测：小写词汇双向映射与非法值拒绝。
 */
class SkillSourceTest {

    @Test
    void should_resolveCustom_when_fromValue_given_lowercaseValue() {
        // given // when
        SkillSource source = SkillSource.fromValue("custom");

        // then
        assertEquals(SkillSource.CUSTOM, source);
    }

    @Test
    void should_resolveCatalog_when_fromValue_given_uppercaseValue() {
        // given // when（大小写不敏感）
        SkillSource source = SkillSource.fromValue("CATALOG");

        // then
        assertEquals(SkillSource.CATALOG, source);
    }

    @Test
    void should_returnLowercaseWords_when_value_given_bothConstants() {
        // given // when // then
        assertEquals("catalog", SkillSource.CATALOG.value());
        assertEquals("custom", SkillSource.CUSTOM.value());
    }

    @Test
    void should_throwException_when_fromValue_given_unknownSource() {
        // given // when // then（旧品牌词汇不再被接受）
        assertThrows(IllegalArgumentException.class, () -> SkillSource.fromValue("workspace"));
    }

    @Test
    void should_throwException_when_fromValue_given_blank() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> SkillSource.fromValue("  "));
    }
}