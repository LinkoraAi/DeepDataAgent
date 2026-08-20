package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 技能（运行时领域值对象，框架无关注）。
 * <p>由 {@code SkillPackageMaterializer}（infrastructure）从发布语言
 * {@code ResolvedSkillDTO} 物化而来：解压技能包 ZIP → 解析 {@code SKILL.md}
 * （YAML frontmatter 取 name / description + 指令正文）→ 本值对象。
 * 工厂据此构建 AgentScope {@code AgentSkill}，本对象本身不依赖框架类型。</p>
 *
 * @param name         技能名称（SKILL.md frontmatter name，缺省回退台账 name）
 * @param description  技能描述（SKILL.md frontmatter description，缺省回退台账 description）
 * @param skillContent SKILL.md 指令正文（不含 frontmatter）
 * @param resources    技能包内资源文件（相对路径 → 文本内容，可为空）
 */
public record Skill(
        String name,
        String description,
        String skillContent,
        Map<String, String> resources
) {

    public Skill {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        if (skillContent == null) {
            throw new IllegalArgumentException("技能内容不能为空");
        }
        resources = resources == null ? Map.of() : Map.copyOf(resources);
    }
}