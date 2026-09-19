package com.linkroa.deepdataagent.skill.api.dto;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 技能内容装配契约（发布语言 DTO，纯 record 无业务逻辑）。
 * <p>由 skill BC 出版：将技能资产指定版本的内容（SKILL.md 正文 + 资源映射）连同名称 / 描述 /
 * 目录名一并提供给消费方（agent 运行时装配链）。跨 BC 仅共享文本内容与元数据，
 * 不泄露 skill BC 领域枚举、不携带技能包 ZIP 原始字节与内部校验值。</p>
 * <p><b>资源为文本形态</b>：上游框架技能资源契约（{@code AgentSkill.resources}）只接受文本，
 * 本 DTO 为运行时装配置面的出口，二进制资源在此按 UTF-8 降级（skill BC 的存储与版本 zip
 * 下载面另行保持字节无损）。</p>
 *
 * @param skillId      技能业务 ID（前缀 {@code skill_}）
 * @param version      版本键（创建时刻 epoch 微秒字符串）
 * @param name         frontmatter name（技能名，等于包顶级目录名）
 * @param description  frontmatter description
 * @param directory    包顶级目录名（恒等于 {@code name}，运行时物化目录名）
 * @param skillContent SKILL.md 正文（可空表示内容缺失）
 * @param resources    资源文件相对路径 → 文本内容（可空，归一为不可变 Map）
 */
public record SkillContentDTO(
        String skillId,
        String version,
        String name,
        String description,
        String directory,
        String skillContent,
        Map<String, String> resources
) {

    /**
     * 紧凑构造器：契约不变量校验（skillId 前缀、版本键、名称 / 目录非空且一致、资源归一不可变）。
     *
     * @throws IllegalArgumentException skillId 非法 / 版本键为空 / 名称或目录为空 / 目录名与名称不一致
     */
    public SkillContentDTO {
        if (StringUtils.isBlank(skillId) || !skillId.startsWith("skill_")) {
            throw new IllegalArgumentException("技能ID非法");
        }
        if (StringUtils.isBlank(version)) {
            throw new IllegalArgumentException("技能版本键不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        if (!name.equals(directory)) {
            throw new IllegalArgumentException("技能目录名必须等于技能名称");
        }
        resources = resources == null ? Map.of() : Map.copyOf(resources);
    }
}