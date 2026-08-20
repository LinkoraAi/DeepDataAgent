package com.linkroa.deepdataagent.agent.application.contract;

import org.apache.commons.lang3.StringUtils;

/**
 * 技能运行时装配契约（发布语言 DTO，Published Language）。
 * <p>由 agent BC 在应用边界出版：将 {@code agent_version.skillIds} 中的挂载引用
 * 解析为「技能包原始字节」，供 runtime BC 物化为 AgentScope {@code AgentSkill}。
 * 跨 BC 只共享技能包原始字节与元数据，不泄露 agent BC 领域枚举 / 仓储。</p>
 *
 * @param skillId       技能业务 ID
 * @param versionNumber 技能版本号（版本锁定）
 * @param name          技能名称（台账）
 * @param description   技能描述（可空）
 * @param storageKey    存储键（诊断/溯源用）
 * @param content       技能包 ZIP 原始字节
 */
public record ResolvedSkillDTO(
        String skillId,
        int versionNumber,
        String name,
        String description,
        String storageKey,
        byte[] content
) {

    /**
     * 紧凑构造器：契约边界校验
     */
    public ResolvedSkillDTO {
        if (StringUtils.isBlank(skillId)) {
            throw new IllegalArgumentException("技能ID不能为空");
        }
        if (versionNumber < 1) {
            throw new IllegalArgumentException("技能版本号必须大于0");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("技能包内容不能为空");
        }
    }

    /**
     * 脱敏 toString：技能包字节不随日志/异常链输出（仅保留长度）。
     */
    @Override
    public String toString() {
        return "ResolvedSkillDTO[skillId=" + skillId
                + ", versionNumber=" + versionNumber
                + ", name=" + name
                + ", description=" + description
                + ", storageKey=" + storageKey
                + ", contentBytes=" + content.length + "]";
    }
}