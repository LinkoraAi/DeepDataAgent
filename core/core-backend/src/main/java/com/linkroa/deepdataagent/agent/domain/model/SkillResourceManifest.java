package com.linkroa.deepdataagent.agent.domain.model;

import java.util.List;

/**
 * 技能包结构化资源元数据（值对象）。
 *
 * <p>封装技能包内除 {@code SKILL.md} 之外的结构化资源文件路径清单：
 * {@code references}（参考资料文件）与 {@code scripts}（脚本文件）。
 * 以强类型替代裸 {@code String}/{@code Map}，紧凑构造器内归一化（null → 空列表）。</p>
 *
 * @param references 参考资料文件相对路径
 * @param scripts    脚本文件相对路径
 */
public record SkillResourceManifest(
        List<String> references,
        List<String> scripts
) {

    public SkillResourceManifest {
        references = references == null ? List.of() : List.copyOf(references);
        scripts = scripts == null ? List.of() : List.copyOf(scripts);
    }

    /**
     * 创建结构化资源清单
     */
    public static SkillResourceManifest create(List<String> references, List<String> scripts) {
        return new SkillResourceManifest(references, scripts);
    }

    /**
     * 空清单（无 references / scripts）
     */
    public static SkillResourceManifest empty() {
        return new SkillResourceManifest(List.of(), List.of());
    }
}