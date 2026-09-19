package com.linkroa.deepdataagent.skill.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 技能内容值对象（SKILL.md 原文 + 资源文件字节映射），作为技能资产端口的存取载荷。
 * <p>SKILL.md 以含 YAML frontmatter 的完整原文存储（下载归档可原样还原整包）；
 * {@code resources} 为「包顶级目录内的资源相对路径 → 原始字节」映射，缺省为空。
 * 资源以原始字节承载（上传解析期不做字符解码），存储与 zip 下载可逐字节还原二进制资源；
 * 仅在运行时装配置面（上游框架 {@code AgentSkill.resources} 只接受文本）按 UTF-8 降级为文本。
 * 内容变更必然生成新的不可变版本，本值对象不承载版本键与校验值（由 {@link SkillVersion} 表达）。
 * 体积上限（zip ≤50MB / 解压总量 ≤50MB）由 {@link SkillPackage} 解析阶段裁决。</p>
 *
 * @param markdown  SKILL.md 完整原文（非空）
 * @param resources 资源文件相对路径 → 原始字节（可空，归一为不可变 Map；字节数组按只读约定使用）
 */
public record SkillContent(String markdown, Map<String, byte[]> resources) {

    /** 保留文件名：技能正文落盘名，资源键不得使用（否则覆盖正文），大小写不敏感比较。 */
    public static final String RESERVED_SKILL_FILE = "SKILL.md";

    /**
     * 紧凑构造器：不变量校验（正文非空、资源键非空且非保留名、资源值非空、归一为不可变）。
     *
     * @throws IllegalArgumentException 正文为空 / 资源键为空或占用保留名 / 资源值为空
     */
    public SkillContent {
        if (StringUtils.isBlank(markdown)) {
            throw new IllegalArgumentException("SKILL.md 内容不能为空");
        }
        if (resources == null || resources.isEmpty()) {
            resources = Map.of();
        } else {
            Map<String, byte[]> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                String key = entry.getKey();
                if (StringUtils.isBlank(key)) {
                    throw new IllegalArgumentException("技能资源路径不能为空");
                }
                if (RESERVED_SKILL_FILE.equalsIgnoreCase(key.trim())) {
                    throw new IllegalArgumentException("技能资源路径不得使用保留文件名 " + RESERVED_SKILL_FILE);
                }
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("技能资源内容不能为空: " + key);
                }
                normalized.put(key, entry.getValue());
            }
            resources = Map.copyOf(normalized);
        }
    }

    /**
     * 无资源的纯文本技能内容。
     *
     * @param markdown SKILL.md 原文
     * @return 技能内容值对象
     */
    public static SkillContent of(String markdown) {
        return new SkillContent(markdown, Map.of());
    }

    /**
     * SKILL.md 正文的 UTF-8 字节长度。
     *
     * @return 字节长度
     */
    public long contentSize() {
        return markdown.getBytes(StandardCharsets.UTF_8).length;
    }
}