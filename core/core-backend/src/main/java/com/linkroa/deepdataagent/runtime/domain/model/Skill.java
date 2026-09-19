package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 技能（运行时领域值对象，框架无关注）。
 * <p>由 {@code SkillAssemblyConvert}（application.convert 防腐映射，{@code materialize} 批量入口）
 * 从发布语言 {@code ResolvedSkillDTO} 物化而来：工作区文件技能（SKILL.md 指令正文 +
 * 资源映射）直接包裹为本值对象。工厂据此构建 AgentScope {@code AgentSkill}，
 * 本对象本身不依赖框架类型。</p>
 *
 * @param name         技能名称（frontmatter name，缺失回退目录名）
 * @param description  技能描述（可空）
 * @param skillContent SKILL.md 指令正文（不含 frontmatter）
 * @param resources    技能资源文件（相对路径 → 文本内容，可为空）
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