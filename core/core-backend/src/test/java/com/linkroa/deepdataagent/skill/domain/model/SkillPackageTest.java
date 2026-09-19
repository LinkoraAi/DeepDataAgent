package com.linkroa.deepdataagent.skill.domain.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SkillPackage} 技能包值对象单测：frontmatter 元数据不变量与资源大小清单派生。
 */
class SkillPackageTest {

    @Test
    void should_holdFrontmatterAndContent_when_constructor_given_validPackage() {
        // given // when
        SkillPackage skillPackage = new SkillPackage("code-review", "代码评审",
                new SkillContent("# 指令", Map.of("references/a.md", text("x"))));

        // then
        assertEquals("code-review", skillPackage.name());
        assertEquals("代码评审", skillPackage.description());
        assertEquals("# 指令", skillPackage.content().markdown());
    }

    @Test
    void should_computeResourceSizes_when_resourceSizes_given_multibyteResources() {
        // given
        SkillPackage skillPackage = new SkillPackage("code-review", "d",
                new SkillContent("body", Map.of("references/a.md", text("中文"))));

        // when
        Map<String, Long> sizes = skillPackage.resourceSizes();

        // then（UTF-8 编码后的字节长度）
        assertEquals(6L, sizes.get("references/a.md"));
    }

    @Test
    void should_returnEmptySizes_when_resourceSizes_given_noResources() {
        // given
        SkillPackage skillPackage = new SkillPackage("code-review", "d", SkillContent.of("body"));

        // when // then
        assertEquals(Map.of(), skillPackage.resourceSizes());
    }

    @Test
    void should_throwException_when_constructor_given_invalidName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new SkillPackage("Code Review", "d", SkillContent.of("body")));
    }

    @Test
    void should_throwException_when_constructor_given_blankDescription() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new SkillPackage("code-review", "  ", SkillContent.of("body")));
    }

    @Test
    void should_throwException_when_constructor_given_nullContent() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> new SkillPackage("code-review", "d", null));
    }

    /** UTF-8 文本资源字节夹具。 */
    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}