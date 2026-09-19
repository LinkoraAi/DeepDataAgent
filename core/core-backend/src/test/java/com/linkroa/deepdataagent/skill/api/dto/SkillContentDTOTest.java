package com.linkroa.deepdataagent.skill.api.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillContentDTO} 发布语言契约边界单测。
 * <p>锁定 epoch 版本键、名称 / 目录一致性不变量与资源归一。</p>
 */
class SkillContentDTOTest {

    @Test
    void should_holdFields_when_constructor_given_validDto() {
        // given // when
        SkillContentDTO dto = new SkillContentDTO("skill_abc", "1759178010641129", "code-review", "描述",
                "code-review", "# 指令", Map.of("scripts/run.sh", "echo hi"));

        // then
        assertEquals("1759178010641129", dto.version());
        assertEquals("code-review", dto.directory());
        assertEquals("# 指令", dto.skillContent());
        assertEquals(1, dto.resources().size());
    }

    @Test
    void should_normalizeNullResources_when_constructor_given_nullResources() {
        // given // when
        SkillContentDTO dto = new SkillContentDTO("skill_abc", "1", "code-review", "描述",
                "code-review", "body", null);

        // then
        assertTrue(dto.resources().isEmpty());
    }

    @Test
    void should_throwException_when_constructor_given_invalidSkillIdPrefix() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new SkillContentDTO("bad_id", "1", "code-review", "描述", "code-review",
                        "body", Map.of()));
    }

    @Test
    void should_throwException_when_constructor_given_blankVersion() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new SkillContentDTO("skill_abc", "  ", "code-review", "描述", "code-review",
                        "body", Map.of()));
    }

    @Test
    void should_throwException_when_constructor_given_blankName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new SkillContentDTO("skill_abc", "1", "  ", "描述", "code-review",
                        "body", Map.of()));
    }

    @Test
    void should_throwException_when_constructor_given_directoryMismatch() {
        // given // when // then（目录名必须等于名称，运行时物化目录名不可漂移）
        assertThrows(IllegalArgumentException.class,
                () -> new SkillContentDTO("skill_abc", "1", "code-review", "描述", "other-dir",
                        "body", Map.of()));
    }
}