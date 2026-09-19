package com.linkroa.deepdataagent.skill.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 技能来源领域枚举（Skill 壳对象 {@code source} 字段的契约词汇）。
 * <ul>
 *   <li>{@link #CATALOG}——<b>平台目录技能</b>：内容由平台技能目录统一维护，
 *       本系统只持有目录引用，本期无数据来源，仅保留词汇与过滤位；</li>
 *   <li>{@link #CUSTOM}——<b>本系统自建技能</b>：用户以文件包上传、由本系统管理其版本。</li>
 * </ul>
 */
public enum SkillSource {

    /** 平台目录技能（内容不由本系统维护）。 */
    CATALOG("catalog"),
    /** 本系统自建技能（资产落盘、版本由本系统管理）。 */
    CUSTOM("custom");

    private final String value;

    SkillSource(String value) {
        this.value = value;
    }

    /**
     * 对外序列化 / 落库的小写词汇。
     *
     * @return 小写来源串（{@code catalog} / {@code custom}）
     */
    public String value() {
        return value;
    }

    /**
     * 由小写词汇解析枚举。
     *
     * @param value 来源串（大小写不敏感）
     * @return 匹配的枚举
     * @throws IllegalArgumentException 空值或未知来源
     */
    public static SkillSource fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("技能来源不能为空");
        }
        for (SkillSource source : values()) {
            if (source.value.equalsIgnoreCase(value.trim())) {
                return source;
            }
        }
        throw new IllegalArgumentException("未知技能来源: " + value);
    }
}