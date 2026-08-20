package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.agent.application.contract.ResolvedSkillDTO;
import com.linkroa.deepdataagent.runtime.domain.model.Skill;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillPackageMaterializer} 技能包物化单测：ZIP → SKILL.md 解析。
 */
class SkillPackageMaterializerTest {

    private final SkillPackageMaterializer materializer = new SkillPackageMaterializer();

    @Test
    void should_materializeSkill_when_materialize_given_zipWithSkillMd() {
        // given
        String skillMd = "---\nname: code-reviewer\ndescription: 代码评审技能\n---\n# Code Reviewer\n指令正文";
        ResolvedSkillDTO dto = new ResolvedSkillDTO("s-1", 3, "台账名称", "台账描述", "s1-v3.zip", zip(skillMd));

        // when
        Skill skill = materializer.materialize(dto);

        // then（frontmatter 的 name/description 优先，正文经解析）
        assertEquals("code-reviewer", skill.name());
        assertEquals("代码评审技能", skill.description());
        assertTrue(skill.skillContent().contains("指令正文"));
    }

    @Test
    void should_fallbackToLedger_when_materialize_given_skillMdWithoutName() {
        // given（SKILL.md 无 frontmatter name → 回退台账 name）
        String skillMd = "# Code Reviewer\n指令正文";
        ResolvedSkillDTO dto = new ResolvedSkillDTO("s-1", 3, "台账名称", "台账描述", "s1-v3.zip", zip(skillMd));

        // when
        Skill skill = materializer.materialize(dto);

        // then
        assertEquals("台账名称", skill.name());
        assertTrue(skill.skillContent().contains("指令正文"));
    }

    @Test
    void should_throw_when_materialize_given_zipWithoutSkillMd() {
        // given（技能包缺少 SKILL.md）
        ResolvedSkillDTO dto = new ResolvedSkillDTO("s-1", 3, "n", "d", "k", zipEntry("README.md", "无技能"));

        // when & then
        assertThrows(IllegalStateException.class, () -> materializer.materialize(dto));
    }

    private byte[] zip(String skillMd) {
        return zipEntry("code-reviewer/SKILL.md", skillMd);
    }

    private byte[] zipEntry(String name, String content) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                zos.putNextEntry(new ZipEntry(name));
                zos.write(content.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}