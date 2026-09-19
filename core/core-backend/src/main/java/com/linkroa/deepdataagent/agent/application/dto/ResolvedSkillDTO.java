package com.linkroa.deepdataagent.agent.application.dto;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 技能运行时装配契约（应用层物化 DTO，仅进程内流转，永不发布）。
 * <p>由 agent BC 在应用边界出版：将 {@code agent_version.skills} 中的技能引用
 * 解析为工作区文件技能内容（SKILL.md 指令正文 + 资源文件映射），供 runtime BC
 * 物化为 AgentScope {@code AgentSkill}。跨 BC 只共享文本内容与元数据，
 * 不泄露 workspace / agent BC 领域枚举、不携带技能包 ZIP 原始字节。</p>
 *
 * @param dirName      技能目录名
 * @param name         技能名（frontmatter name，缺失回退目录名）
 * @param description  技能描述（可空）
 * @param skillContent SKILL.md 指令正文（不含 frontmatter）
 * @param resources    资源文件相对路径 → 文本内容（可为空）
 */
public record ResolvedSkillDTO(
        String dirName,
        String name,
        String description,
        String skillContent,
        Map<String, String> resources
) {

    /**
     * 紧凑构造器：契约边界校验
     */
    public ResolvedSkillDTO {
        if (StringUtils.isBlank(dirName)) {
            throw new IllegalArgumentException("技能目录名不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        if (skillContent == null) {
            throw new IllegalArgumentException("技能正文不能为空");
        }
        resources = resources == null ? Map.of() : Map.copyOf(resources);
    }
}