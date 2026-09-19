package com.linkroa.deepdataagent.skill.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 技能包值对象（multipart 上传解析产物，不可变）。
 * <p>由 {@code SkillPackageParser} 从 zip / 裸文件树统一解压后产出：{@code name} /
 * {@code description} 取自顶级 {@code SKILL.md} 的 YAML frontmatter，内容为完整 SKILL.md 原文
 * + 包顶级目录内的其余文件字节映射（原样保留字节，二进制资源不失真）。跨版本 {@code name} 一致性与版本键生成由应用服务裁决。</p>
 *
 * @param name        frontmatter name（{@code ^[a-z0-9][a-z0-9_-]*$}，≤64，且等于包顶级目录名）
 * @param description frontmatter description（非空，≤5120）
 * @param content     技能内容（SKILL.md 原文 + 资源映射）
 */
public record SkillPackage(String name, String description, SkillContent content) {

    /**
     * 紧凑构造器：不变量校验（名称正则与边界、描述非空与边界、内容非空）。
     *
     * @throws IllegalArgumentException 任一不变量不满足
     */
    public SkillPackage {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        if (name.length() > SkillVersion.MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("技能名称长度不能超过" + SkillVersion.MAX_NAME_LENGTH + "个字符");
        }
        if (!name.matches(SkillVersion.NAME_PATTERN)) {
            throw new IllegalArgumentException("技能名称须匹配 " + SkillVersion.NAME_PATTERN + ": " + name);
        }
        if (StringUtils.isBlank(description)) {
            throw new IllegalArgumentException("技能描述不能为空");
        }
        if (description.length() > SkillVersion.MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("技能描述不能超过" + SkillVersion.MAX_DESCRIPTION_LENGTH + "个字符");
        }
        if (content == null) {
            throw new IllegalArgumentException("技能包内容不能为空");
        }
    }

    /**
     * 资源文件相对路径 → 字节长度清单（版本元数据落库用）。
     *
     * @return 不可变资源大小映射
     */
    public Map<String, Long> resourceSizes() {
        return content.resources().entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> (long) entry.getValue().length));
    }
}