package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

/**
 * 内置实体类型枚举（系统维护，不可配置）。
 * <p>图谱抽取内置 11 类实体，{@code OTHER} 为强制保留类型：既不属于可配置的自定义集合，
 * 也不允许用户以同名（英文代码或中文名）方式自定义。匹配规则为大小写归一后
 * 与英文代码或中文名一致即视为同名。</p>
 */
public enum BuiltinEntityType {

    /** 人 */
    PERSON("人"),

    /** 生物 */
    ORGANISM("生物"),

    /** 组织 */
    ORGANIZATION("组织"),

    /** 地点 */
    LOCATION("地点"),

    /** 事件 */
    EVENT("事件"),

    /** 概念 */
    CONCEPT("概念"),

    /** 方法 */
    METHOD("方法"),

    /** 内容 */
    CONTENT("内容"),

    /** 数据 */
    DATA("数据"),

    /** 制品 */
    ARTIFACT("制品"),

    /** 自然物 */
    NATURAL_OBJECT("自然物"),

    /** 其他（强制保留类型，MUST NOT 出现在可配置集合中） */
    OTHER("其他");

    /** 内置类型中文名 */
    private final String cnName;

    BuiltinEntityType(String cnName) {
        this.cnName = cnName;
    }

    /**
     * 获取内置类型中文名。
     *
     * @return 中文名
     */
    public String cnName() {
        return cnName;
    }

    /**
     * 判断给定名称是否与本类型同名（大小写归一后比较英文代码，或等于中文名）。
     *
     * @param name 待判断名称
     * @return 同名返回 true
     */
    public boolean matches(String name) {
        return StringUtils.equalsIgnoreCase(name(), name) || StringUtils.equals(cnName, name);
    }

    /**
     * 查找与给定名称同名的内置类型。
     *
     * @param name 实体类型名称
     * @return 命中的内置类型；未命中返回空
     */
    public static Optional<BuiltinEntityType> find(String name) {
        for (BuiltinEntityType candidate : values()) {
            if (candidate.matches(name)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
