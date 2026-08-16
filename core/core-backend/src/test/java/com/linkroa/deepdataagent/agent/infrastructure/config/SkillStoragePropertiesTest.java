package com.linkroa.deepdataagent.agent.infrastructure.config;

import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStorageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 技能存储类型配置校验单测（未知/未实现存储类型 fail-fast）
 */
class SkillStoragePropertiesTest {

    @Test
    void should_returnLocalFile_when_resolveStorageType_given_localFile() {
        // given
        SkillStorageProperties properties = new SkillStorageProperties();
        properties.setStorageType("LOCAL_FILE");

        // when
        SkillStorageType type = properties.resolveStorageType();

        // then
        assertEquals(SkillStorageType.LOCAL_FILE, type);
    }

    @Test
    void should_failFast_when_resolveStorageType_given_oss() {
        // given
        SkillStorageProperties properties = new SkillStorageProperties();
        properties.setStorageType("OSS");

        // when
        // then
        assertThrows(IllegalStateException.class, properties::resolveStorageType);
    }

    @Test
    void should_failFast_when_resolveStorageType_given_blank() {
        // given
        SkillStorageProperties properties = new SkillStorageProperties();
        properties.setStorageType("   ");

        // when
        // then
        assertThrows(IllegalStateException.class, properties::resolveStorageType);
    }

    @Test
    void should_failFast_when_validateStorageType_given_unknownValue() {
        // given
        SkillStorageProperties properties = new SkillStorageProperties();
        properties.setStorageType("S3_UNKNOWN");

        // when
        // then
        assertThrows(IllegalStateException.class, properties::validateStorageType);
    }
}