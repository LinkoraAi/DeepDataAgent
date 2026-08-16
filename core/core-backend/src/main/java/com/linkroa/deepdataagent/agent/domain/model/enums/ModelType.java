package com.linkroa.deepdataagent.agent.domain.model.enums;

/**
 * 模型类型
 */
public enum ModelType {

    /** 对话/生成模型 */
    CHAT(1),
    /** 向量嵌入模型（需配置 vector_dimension） */
    EMBEDDING(2);

    private final int code;

    ModelType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 按数据库码值解析模型类型
     *
     * @param code 数据库存储的整型码值
     * @return 模型类型枚举
     * @throws IllegalArgumentException 未知码值
     */
    public static ModelType fromCode(int code) {
        for (ModelType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知模型类型码值: " + code);
    }
}