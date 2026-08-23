package com.linkroa.deepdataagent.agent.infrastructure.util;

import com.linkroa.deepdataagent.agent.domain.model.SkillResourceManifest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SkillPackageInspector} 技能包结构检查器单测：SKILL.md 必需 + references/scripts 提取。
 */
class SkillPackageInspectorTest {

    private final SkillPackageInspector inspector = new SkillPackageInspector();

    @Test
    void should_extractManifest_when_inspect_given_skillMdWithReferencesAndScripts() {
        // given
        byte[] content = zip(
                entry("SKILL.md", "---\nname: code-reviewer\n---\n正文"),
                entry("references/api.md", "参考文档"),
                entry("scripts/run.py", "print('ok')"));

        // when
        SkillResourceManifest manifest = inspector.inspect(content);

        // then
        assertEquals(List.of("references/api.md"), manifest.references());
        assertEquals(List.of("scripts/run.py"), manifest.scripts());
    }

    @Test
    void should_extractEmptyManifest_when_inspect_given_skillMdOnly() {
        // given
        byte[] content = zip(entry("SKILL.md", "---\nname: x\n---\n正文"));

        // when
        SkillResourceManifest manifest = inspector.inspect(content);

        // then
        assertEquals(SkillResourceManifest.empty(), manifest);
    }

    @Test
    void should_throw_when_inspect_given_missingSkillMd() {
        // given
        byte[] content = zip(entry("references/api.md", "参考文档"));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(content));
    }

    @Test
    void should_throw_when_inspect_given_invalidZip() {
        // given
        byte[] content = "不是合法 ZIP".getBytes(StandardCharsets.UTF_8);

        // when & then
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(content));
    }

    private record ZipEntryContent(String name, String content) {
    }

    private ZipEntryContent entry(String name, String content) {
        return new ZipEntryContent(name, content);
    }

    private byte[] zip(ZipEntryContent... entries) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                for (ZipEntryContent entry : entries) {
                    zos.putNextEntry(new ZipEntry(entry.name()));
                    zos.write(entry.content().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}