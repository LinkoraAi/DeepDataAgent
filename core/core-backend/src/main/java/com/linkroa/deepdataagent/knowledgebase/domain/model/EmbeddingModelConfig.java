package com.linkroa.deepdataagent.knowledgebase.domain.model;

/**
 * 嵌入模型配置值对象。
 *
 * @param modelProfileId 嵌入模型 profileId（引用 agent BC 模型注册表）
 */
public record EmbeddingModelConfig(
        String modelProfileId
) {

    public EmbeddingModelConfig {
        if (modelProfileId == null || modelProfileId.isBlank()) {
            throw new IllegalArgumentException("嵌入模型ID不能为空");
        }
    }
}
