package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.agent.application.contract.ResolvedSkillDTO;
import com.linkroa.deepdataagent.runtime.domain.model.Skill;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能包物化器：将发布语言 {@link ResolvedSkillDTO}（技能包 ZIP 原始字节）物化为
 * 框架无关注值对象 {@link Skill}（解压 ZIP → 解析 SKILL.md frontmatter + 指令正文）。
 * <p>技能包以 SKILL.md 为内容契约（YAML frontmatter：name / description + 指令正文）。
 * 本期仅物化 SKILL.md 指令集，references/scripts 资源文件落地后续补充。</p>
 */
@Slf4j
@Component
public class SkillPackageMaterializer {

    private static final String SKILL_MD_SUFFIX = "/SKILL.md";

    /**
     * 批量物化：缺失/损坏技能包跳过（不阻断装配），其余正常物化。
     */
    public List<Skill> materialize(List<ResolvedSkillDTO> dtos) {
        List<Skill> skills = new ArrayList<>();
        if (dtos == null) {
            return skills;
        }
        for (ResolvedSkillDTO dto : dtos) {
            try {
                skills.add(materialize(dto));
            } catch (RuntimeException e) {
                log.warn("技能包损坏或缺失 SKILL.md，跳过物化: skillId={} version={}",
                        dto.skillId(), dto.versionNumber(), e);
            }
        }
        return skills;
    }

    /**
     * 单个技能物化：解压 SKILL.md 并以 frontmatter 的 name/description 优先，台账值为回退。
     */
    public Skill materialize(ResolvedSkillDTO dto) {
        String skillMd = extractSkillMd(dto.content());
        var parsed = MarkdownSkillParser.parse(skillMd);
        Map<String, Object> metadata = parsed.getMetadata();

        String name = firstNonBlank(stringOf(metadata.get("name")), dto.name());
        String description = firstNonBlank(stringOf(metadata.get("description")), dto.description());
        return new Skill(name, description, parsed.getContent(), Map.of());
    }

    /** 解压技能包并返回 SKILL.md 文本；缺失或未找到时抛非法状态。 */
    private String extractSkillMd(byte[] content) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (name.endsWith(SKILL_MD_SUFFIX) || name.equalsIgnoreCase("SKILL.md")) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("技能包解压失败", e);
        }
        throw new IllegalStateException("技能包缺少 SKILL.md");
    }

    private static String firstNonBlank(String primary, String fallback) {
        return StringUtils.isNotBlank(primary) ? primary : fallback;
    }

    private static String stringOf(Object value) {
        return value instanceof String s ? s : null;
    }
}