package com.linkroa.deepdataagent.agent.domain.model.enums;

/**
 * 模型类型（业务码值与各消费槽位绑定口径的唯一真相源）。
 * <p>码值为持久化与线格式共用口径，MUST NOT 依赖枚举序数；
 * 变更码值意味着存量数据语义迁移，须停机一次性切换。</p>
 */
public enum ModelType {

    /** 对话 LLM（智能体大脑；绑定智能体模型槽位） */
    CHAT_LLM(1, "对话LLM"),
    /** 视觉对话 VLM（多模态理解；绑定知识库多模态槽位，亦可作智能体对话模型） */
    CHAT_VLM(2, "视觉对话"),
    /** 向量嵌入模型（需配置 vector_dimension；绑定知识库嵌入槽位） */
    EMBEDDING(3, "嵌入"),
    /** 重排模型（检索结果重排序；绑定检索策略重排槽位） */
    RERANK(4, "重排");

    private final int code;

    /** 中文展示名（用于校验错误信息，便于用户定位期望类型） */
    private final String label;

    ModelType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 数据库存取与线格式使用的整型码值。
     *
     * @return 码值（1=对话LLM，2=视觉对话，3=嵌入，4=重排）
     */
    public int getCode() {
        return code;
    }

    /**
     * 中文展示名（用于错误信息拼装）。
     *
     * @return 展示名，如「视觉对话」
     */
    public String getLabel() {
        return label;
    }

    /**
     * 按业务码值解析模型类型。
     *
     * @param code 业务码值
     * @return 模型类型枚举
     * @throws IllegalArgumentException 未知码值（含 0 与 5 及以上）
     */
    public static ModelType fromCode(int code) {
        for (ModelType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知模型类型码值: " + code + "（合法值：1=对话LLM，2=视觉对话，3=嵌入，4=重排）");
    }
}
