package com.linkroa.deepdataagent.skill.domain.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillContent} 技能内容值对象单测。
 * <p>体积上限（包 50MB / 解压总量 50MB）已上移至解析阶段裁决，本层仅保留正文 / 资源
 * 非空与保留名校验。</p>
 */
class SkillContentTest {

    @Test
    void should_holdMarkdownAndEmptyResources_when_of_given_onlyMarkdown() {
        // given // when
        SkillContent content = SkillContent.of("# Skill");

        // then
        assertEquals("# Skill", content.markdown());
        assertTrue(content.resources().isEmpty());
    }

    @Test
    void should_computeUtf8Size_when_contentSize_given_multibyteMarkdown() {
        // given
        SkillContent content = new SkillContent("中文", Map.of());

        // when
        long size = content.contentSize();

        // then
        assertEquals(6L, size);
    }

    @Test
    void should_throwException_when_constructor_given_blankMarkdown() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> new SkillContent("  ", Map.of()));
    }

    @Test
    void should_normalizeNullResourcesToEmpty_when_constructor_given_nullResources() {
        // given // when
        SkillContent content = new SkillContent("body", null);

        // then
        assertTrue(content.resources().isEmpty());
    }

    @Test
    void should_rejectReservedSkillFileKey_when_constructor_given_skillMdResourceKey() {
        // given // when // then（大小写不敏感 + trim 后仍与正文文件冲突者一律拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> new SkillContent("body", Map.of("SKILL.md", text("覆盖正文"))));
        assertThrows(IllegalArgumentException.class,
                () -> new SkillContent("body", Map.of(" skill.MD ", text("覆盖正文"))));
    }

    @Test
    void should_allowNestedSkillFileKey_when_constructor_given_subDirectorySameName() {
        // given（子目录下的同名文件不构成正文覆盖，相对路径不同 → 合法）
        // when
        SkillContent content = new SkillContent("body", Map.of("references/SKILL.md", text("引用")));

        // then
        assertEquals(1, content.resources().size());
    }

    @Test
    void should_rejectNullKeyOrValue_when_constructor_given_nullResourceEntry() {
        // given（Map.copyOf 的 NPE 前移到构造器，避免落 500）
        Map<String, byte[]> nullKey = new HashMap<>();
        nullKey.put(null, text("内容"));
        Map<String, byte[]> nullValue = new HashMap<>();
        nullValue.put("references/a.md", null);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> new SkillContent("body", nullKey));
        assertThrows(IllegalArgumentException.class, () -> new SkillContent("body", nullValue));
    }

    @Test
    void should_acceptLargeContent_when_constructor_given_deepTree() {
        // given（体积上限改由 SkillPackageParser 裁决，本层不再拒绝）
        Map<String, byte[]> resources = Map.of("references/a.md", text("x".repeat(200_000)));

        // when
        SkillContent content = new SkillContent("body", resources);

        // then
        assertEquals(1, content.resources().size());
    }

    @Test
    void should_keepRawBytes_when_resources_given_binaryResource() {
        // given（非 UTF-8 可解码字节：字符解码会替换为 U+FFFD，字节承载可原样保留）
        byte[] binary = {(byte) 0x89, 'P', 'N', 'G', 0x00, (byte) 0xFF, (byte) 0xFE};

        // when
        SkillContent content = new SkillContent("body", Map.of("assets/logo.png", binary));

        // then
        assertArrayEquals(binary, content.resources().get("assets/logo.png"));
    }

    /** UTF-8 文本资源字节夹具。 */
    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}