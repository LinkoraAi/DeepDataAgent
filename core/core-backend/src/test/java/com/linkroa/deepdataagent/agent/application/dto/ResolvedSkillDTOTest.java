package com.linkroa.deepdataagent.agent.application.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResolvedSkillDTO} 契约边界校验单测。
 */
class ResolvedSkillDTOTest {

    @Test
    void should_build_when_construct_given_validFields() {
        // given // when
        ResolvedSkillDTO dto = new ResolvedSkillDTO("s-1", "code-reviewer", "代码评审", "指令正文",
                Map.of("references/api.md", "参考"));

        // then
        assertEquals("s-1", dto.dirName());
        assertEquals("code-reviewer", dto.name());
        assertEquals("代码评审", dto.description());
        assertEquals("指令正文", dto.skillContent());
        assertEquals("参考", dto.resources().get("references/api.md"));
    }

    @Test
    void should_defaultEmptyResources_when_construct_given_nullResources() {
        // given // when
        ResolvedSkillDTO dto = new ResolvedSkillDTO("s-1", "n", null, "body", null);

        // then
        assertTrue(dto.resources().isEmpty());
    }

    @Test
    void should_throw_when_construct_given_blankDirName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedSkillDTO(" ", "n", null, "body", Map.of()));
    }

    @Test
    void should_throw_when_construct_given_blankName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedSkillDTO("s-1", "", null, "body", Map.of()));
    }

    @Test
    void should_throw_when_construct_given_nullSkillContent() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedSkillDTO("s-1", "n", null, null, Map.of()));
    }
}