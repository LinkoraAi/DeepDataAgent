package com.linkroa.deepdataagent.agent.domain.model.enums;

/**
 * 技能类型（skill_resource.skill_type）
 */
public enum SkillType {

    /** 自定义技能 */
    CUSTOM(1),
    /** 官方预留（预留位） */
    OFFICIAL(2);

    private final int code;

    SkillType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 按数据库码值解析技能类型
     */
    public static SkillType fromCode(int code) {
        for (SkillType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知技能类型码值: " + code);
    }
}